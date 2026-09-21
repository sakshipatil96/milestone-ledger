package dev.sakshi.milestoneledger.reconciliation;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.sakshi.milestoneledger.ingestion.ReceiptFacts;
import dev.sakshi.milestoneledger.ingestion.InboxEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ReconciliationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationWorker.class);
    private static final UUID SYSTEM = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private final UUID owner = UUID.randomUUID();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BankSnapshotClient bank;
    private final InboxEventStore inbox;

    public ReconciliationWorker(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                BankSnapshotClient bank, InboxEventStore inbox) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.bank = bank;
        this.inbox = inbox;
    }

    @Scheduled(fixedDelayString = "${app.reconciliation-worker.fixed-delay-ms:250}",
            initialDelayString = "${app.reconciliation-worker.initial-delay-ms:100}")
    public void poll() {
        Lease lease;
        try { lease = tx.execute(status -> claim()); }
        catch (RuntimeException failure) { LOG.error("Reconciliation claim failed errorCode=DATABASE_UNAVAILABLE"); return; }
        if (lease == null) return;
        try {
            switch (lease.phase()) {
                case "FETCH" -> fetch(lease);
                case "COMPARE" -> tx.executeWithoutResult(status -> compare(lease));
                case "ABSENCE" -> tx.executeWithoutResult(status -> absence(lease));
                case "WAIT" -> tx.executeWithoutResult(status -> finish(lease));
                default -> throw new IllegalStateException("invalid reconciliation phase");
            }
        } catch (BankSnapshotClient.SnapshotFailure failure) {
            try { tx.executeWithoutResult(status -> fetchFailure(lease, failure)); }
            catch (RuntimeException recordingFailure) {
                LOG.error("Reconciliation failure metadata unavailable runId={} errorCode=DATABASE_UNAVAILABLE", lease.id());
            }
        } catch (RuntimeException failure) {
            // A rolled-back step remains durable and is retried after its lease expires.
            LOG.error("Reconciliation step rolled back runId={} phase={} errorCode=STEP_FAILED", lease.id(), lease.phase());
        }
    }

    private Lease claim() {
        Lease found = jdbc.query("""
                select id, phase, source, project_id, account_reference, snapshot_id, as_of,
                       next_cursor, absence_cursor, lease_version, page_count
                from reconciliation_run
                where status in ('QUEUED','RUNNING') and next_attempt_at<=now()
                  and (lease_until is null or lease_until<=now())
                order by created_at, id for update skip locked limit 1
                """, (rs, n) -> new Lease(rs.getObject("id", UUID.class), rs.getString("phase"),
                rs.getString("source"), rs.getObject("project_id", UUID.class), rs.getString("account_reference"),
                rs.getString("snapshot_id"), time(rs, "as_of"), rs.getString("next_cursor"),
                rs.getObject("absence_cursor", UUID.class),
                rs.getLong("lease_version") + 1, rs.getInt("page_count"))).stream().findFirst().orElse(null);
        if (found == null) return null;
        jdbc.update("""
                update reconciliation_run set status='RUNNING', started_at=coalesce(started_at,now()),
                    lease_owner=?, lease_version=lease_version+1, lease_until=now()+interval '15 seconds'
                where id=?
                """, owner, found.id());
        return found;
    }

    private void fetch(Lease lease) {
        long start = System.nanoTime();
        BankSnapshotClient.Page page = bank.fetch(lease.cursor()); // deliberately outside a database transaction
        try {
            tx.executeWithoutResult(status -> stagePage(lease, page));
        } catch (DataIntegrityViolationException invalidPage) {
            throw new BankSnapshotClient.SnapshotFailure("INVALID_SNAPSHOT", false);
        }
        LOG.info("Reconciliation page committed runId={} page={} itemCount={} durationMs={}", lease.id(),
                lease.pageCount() + 1, page.items().size(), (System.nanoTime() - start) / 1_000_000);
    }

    private void stagePage(Lease lease, BankSnapshotClient.Page page) {
        guard(lease);
        if (lease.pageCount() >= 1000 || (lease.snapshotId() != null &&
                (!lease.snapshotId().equals(page.snapshotId()) || !lease.asOf().equals(page.asOf()))))
            throw new BankSnapshotClient.SnapshotFailure("INCONSISTENT_SNAPSHOT", false);
        if (page.nextCursor() != null) {
            int unique = jdbc.update("""
                    insert into reconciliation_cursor(run_id,cursor_value) values (?,?) on conflict do nothing
                    """, lease.id(), page.nextCursor());
            if (unique == 0) throw new BankSnapshotClient.SnapshotFailure("CURSOR_LOOP", false);
        }
        for (BankSnapshotClient.Item item : page.items()) {
            String hash = ReceiptFacts.hash(lease.source(), lease.projectId(), lease.account(),
                    item.bankReceiptId(), item.amount(), item.currency(), item.reference(), item.postedAt());
            int inserted = jdbc.update("""
                    insert into reconciliation_item(run_id,bank_receipt_id,amount_paise,currency,
                        demand_reference,posted_at,fact_hash) values (?,?,?,?,?,?,?)
                    on conflict (run_id,bank_receipt_id) do nothing
                    """, lease.id(), item.bankReceiptId(), item.amount(), item.currency(), item.reference(),
                    Timestamp.from(ReceiptFacts.normalize(item.postedAt())), hash);
            if (inserted == 0) {
                String prior = jdbc.queryForObject("""
                        select fact_hash from reconciliation_item where run_id=? and bank_receipt_id=?
                        """, String.class, lease.id(), item.bankReceiptId());
                if (!hash.equals(prior)) throw new BankSnapshotClient.SnapshotFailure("CONFLICTING_SNAPSHOT_RECORD", false);
            }
        }
        jdbc.update("""
                update reconciliation_run set snapshot_id=?, as_of=?, next_cursor=?, page_count=page_count+1,
                    bank_count=(select count(*) from reconciliation_item where run_id=?),
                    fetch_attempt_count=0, error_code=null, phase=?, lease_owner=null, lease_until=null
                where id=?
                """, page.snapshotId(), Timestamp.from(page.asOf()), page.nextCursor(), lease.id(),
                page.nextCursor() == null ? "COMPARE" : "FETCH", lease.id());
    }

    private void compare(Lease lease) {
        guard(lease);
        Item item = jdbc.query("""
                select bank_receipt_id,amount_paise,currency,demand_reference,posted_at,fact_hash
                from reconciliation_item where run_id=? and outcome='STAGED'
                order by bank_receipt_id limit 1 for update
                """, (rs, n) -> new Item(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getString(6)), lease.id()).stream().findFirst().orElse(null);
        if (item == null) {
            advance(lease.id(), "ABSENCE");
            return;
        }
        List<Existing> existing = jdbc.query("select id,project_id,fact_hash from receipt where source=? and bank_receipt_id=?",
                (rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
                lease.source(), item.id());
        if (existing.isEmpty()) {
            UUID event = UUID.nameUUIDFromBytes(("recovery:" + lease.id() + ":" + item.id()).getBytes(StandardCharsets.UTF_8));
            String eventId = "recovery-" + event;
            inbox.insert(event, lease.source(), eventId, lease.projectId(), lease.account(), item.id(), item.amount(),
                    item.currency(), item.reference(), item.postedAt(), item.hash(), "RECOVERY");
            jdbc.update("update reconciliation_item set outcome='ENQUEUED',inbox_event_id=? where run_id=? and bank_receipt_id=?",
                    event, lease.id(), item.id());
            committed(() -> LOG.info("Reconciliation recovery enqueued runId={} inboxEventId={}", lease.id(), event));
        } else {
            Existing current = existing.getFirst();
            if (!item.hash().equals(current.hash())) {
                discrepancy(lease, lease.projectId().equals(current.projectId()) ? current.id() : null,
                        item.id(), "BANK_RECORD_CONFLICT", "FACTS_CHANGED");
                jdbc.update("update reconciliation_item set outcome='CONFLICT' where run_id=? and bank_receipt_id=?", lease.id(), item.id());
            } else {
                resolve(lease, item.id());
                jdbc.update("update reconciliation_item set outcome='MATCHED' where run_id=? and bank_receipt_id=?", lease.id(), item.id());
            }
        }
        release(lease.id());
    }

    private void absence(Lease lease) {
        guard(lease);
        if (lease.asOf() == null) throw new IllegalStateException("comparison without snapshot");
        List<Local> locals = jdbc.query("""
                select r.id,r.bank_receipt_id,
                    not exists (select 1 from reconciliation_item i
                        where i.run_id=? and i.bank_receipt_id=r.bank_receipt_id) missing
                from receipt r
                where r.source=? and r.project_id=? and r.posted_at<=? and r.recorded_at<=?
                    and (?::uuid is null or r.id>?::uuid)
                order by r.id limit 100
                """, (rs, n) -> new Local(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)),
                lease.id(), lease.source(), lease.projectId(), Timestamp.from(lease.asOf()),
                Timestamp.from(lease.asOf()), lease.absenceCursor(), lease.absenceCursor());
        for (Local local : locals) {
            if (local.missing()) discrepancy(lease, local.id(), local.bankId(),
                    "LOCAL_RECEIPT_NOT_IN_BANK", "ABSENT_FROM_COMPLETE_SNAPSHOT");
        }
        if (locals.isEmpty()) advance(lease.id(), "WAIT");
        else jdbc.update("""
                update reconciliation_run set absence_cursor=?,lease_owner=null,lease_until=null where id=?
                """, locals.getLast().id(), lease.id());
    }

    private void finish(Lease lease) {
        guard(lease);
        List<String> linkedStatuses = jdbc.query("""
                select e.status from reconciliation_item i join inbox_event e on e.id=i.inbox_event_id
                where i.run_id=? order by e.id for update of e
                """, (rs, n) -> rs.getString(1), lease.id());
        if (linkedStatuses.contains("PENDING")) {
            jdbc.update("update reconciliation_run set lease_owner=null,lease_until=null,next_attempt_at=now()+interval '1 second' where id=?", lease.id());
            return;
        }
        jdbc.update("""
                update reconciliation_item i set outcome=case when e.status='FAILED' then 'FAILED'
                    when e.status='CONFLICT' then 'CONFLICT' else 'PROCESSED' end
                from inbox_event e where i.run_id=? and i.inbox_event_id=e.id and i.outcome='ENQUEUED'
                """, lease.id());
        List<UUID> racedConflicts = jdbc.query("""
                select distinct x.id from reconciliation_item i
                join inbox_event e on e.id=i.inbox_event_id
                join exception_case x on x.source=e.source and x.bank_receipt_id=e.bank_receipt_id
                    and x.type='BANK_RECORD_CONFLICT'
                where i.run_id=? and e.status='CONFLICT'
                """, (rs, n) -> rs.getObject(1, UUID.class), lease.id());
        for (UUID id : racedConflicts) jdbc.update("""
                insert into reconciliation_exception(run_id,exception_id) values (?,?) on conflict do nothing
                """, lease.id(), id);
        int recovered = jdbc.queryForObject("""
                select count(*) from reconciliation_item i join financial_entry f on f.inbox_event_id=i.inbox_event_id
                where i.run_id=? and f.kind='RECEIPT'
                """, Integer.class, lease.id());
        int conflicts = jdbc.queryForObject("""
                select count(*) from reconciliation_exception x join exception_case e on e.id=x.exception_id
                where x.run_id=? and e.status='OPEN'
                """, Integer.class, lease.id());
        int failed = jdbc.queryForObject("select count(*) from reconciliation_item where run_id=? and outcome='FAILED'", Integer.class, lease.id());
        String terminal = failed == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
        jdbc.update("""
                update reconciliation_run set status=?, phase='DONE', recovered_count=?, conflict_count=?,
                    failed_count=?, finished_at=now(), lease_owner=null, lease_until=null,
                    error_code=case when ?>0 then 'RECOVERY_EVENT_FAILED' else null end where id=?
                """, terminal, recovered, conflicts, failed, failed, lease.id());
        committed(() -> LOG.info("Reconciliation completed runId={} status={} recoveredCount={} conflictCount={} failedCount={}",
                lease.id(), terminal, recovered, conflicts, failed));
    }

    private void discrepancy(Lease lease, UUID receipt, String bankId, String type, String reason) {
        String key = type + ":" + lease.source() + ":" + bankId;
        List<String> previous = jdbc.query("select status from exception_case where dedupe_key=? for update",
                (rs, n) -> rs.getString(1), key);
        UUID exception = jdbc.queryForObject("""
                insert into exception_case(type,project_id,receipt_id,source,bank_receipt_id,dedupe_key,reason_code)
                values (?,?,?,?,?,?,?) on conflict (dedupe_key) do update set status='OPEN',
                    reason_code=excluded.reason_code, last_seen_at=now(), resolved_at=null returning id
                """, UUID.class, type, lease.projectId(), receipt, lease.source(), bankId, key, reason);
        jdbc.update("insert into reconciliation_exception(run_id,exception_id) values (?,?) on conflict do nothing", lease.id(), exception);
        if (previous.isEmpty() || "RESOLVED".equals(previous.getFirst())) audit(lease.id(), exception,
                previous.isEmpty() ? "DISCREPANCY_CREATED" : "DISCREPANCY_REOPENED");
    }

    private void resolve(Lease lease, String bankId) {
        for (String type : List.of("BANK_RECORD_CONFLICT", "LOCAL_RECEIPT_NOT_IN_BANK")) {
            List<UUID> ids = jdbc.query("""
                    update exception_case set status='RESOLVED',resolved_at=now(),last_seen_at=now()
                    where dedupe_key=? and status='OPEN'
                        and last_seen_at<=least(?::timestamptz,
                            (select created_at from reconciliation_run where id=?)) returning id
                    """, (rs, n) -> rs.getObject(1, UUID.class),
                    type + ":" + lease.source() + ":" + bankId, Timestamp.from(lease.asOf()), lease.id());
            for (UUID id : ids) {
                jdbc.update("insert into reconciliation_exception(run_id,exception_id) values (?,?) on conflict do nothing", lease.id(), id);
                audit(lease.id(), id, "DISCREPANCY_RESOLVED");
            }
        }
    }

    private void audit(UUID runId, UUID exceptionId, String action) {
        jdbc.update("""
                insert into audit_event(actor_id,action,entity_type,entity_id,reason,request_id)
                values (?,?,'EXCEPTION',?,'RECONCILIATION',?)
                """, SYSTEM, action, exceptionId, "run-" + runId);
    }

    private void fetchFailure(Lease lease, BankSnapshotClient.SnapshotFailure failure) {
        guard(lease);
        int attempts = jdbc.queryForObject("select fetch_attempt_count from reconciliation_run where id=?", Integer.class, lease.id()) + 1;
        boolean terminal = !failure.retryable() || attempts >= 5;
        jdbc.update("""
                update reconciliation_run set fetch_attempt_count=?, error_code=?,
                    status=case when ? then 'FAILED' else 'RUNNING' end,
                    phase=case when ? then 'DONE' else 'FETCH' end,
                    finished_at=case when ? then now() else null end,
                    next_attempt_at=now()+(? * interval '1 second'), lease_owner=null,lease_until=null
                where id=?
                """, attempts, failure.code(), terminal, terminal, terminal,
                terminal ? 0 : 1 << (attempts - 1), lease.id());
        committed(() -> LOG.warn("Reconciliation fetch failed runId={} attempt={} terminal={} errorCode={}",
                lease.id(), attempts, terminal, failure.code()));
    }

    private void advance(UUID runId, String phase) {
        jdbc.update("update reconciliation_run set phase=?, lease_owner=null,lease_until=null where id=?", phase, runId);
    }
    private void release(UUID runId) {
        jdbc.update("update reconciliation_run set lease_owner=null,lease_until=null where id=?", runId);
    }
    private void guard(Lease lease) {
        int changed = jdbc.update("""
                update reconciliation_run set lease_until=now()+interval '15 seconds'
                where id=? and lease_owner=? and lease_version=? and lease_until>now()
                    and status='RUNNING' and phase=?
                """, lease.id(), owner, lease.version(), lease.phase());
        if (changed != 1) throw new IllegalStateException("stale reconciliation lease");
    }
    private static void committed(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    private static Instant time(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        var value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
    private record Lease(UUID id, String phase, String source, UUID projectId, String account,
                         String snapshotId, Instant asOf, String cursor, UUID absenceCursor,
                         long version, int pageCount) { }
    private record Item(String id, long amount, String currency, String reference, Instant postedAt, String hash) { }
    private record Existing(UUID id, UUID projectId, String hash) { }
    private record Local(UUID id, String bankId, boolean missing) { }
}
