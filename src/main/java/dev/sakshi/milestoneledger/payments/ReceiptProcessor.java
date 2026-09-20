package dev.sakshi.milestoneledger.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The only automatic money path.  Receipt is locked before demand in every branch. */
@Service
public class ReceiptProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(ReceiptProcessor.class);
    private static final UUID SYSTEM_ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PaymentAllocationService allocationService;

    public ReceiptProcessor(JdbcTemplate jdbc, Clock clock, PaymentAllocationService allocationService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.allocationService = allocationService;
    }

    @Transactional
    public boolean processOneDueEvent() {
        Event event = jdbc.query("""
                select id, source, project_id, account_reference, bank_receipt_id, amount_paise, currency,
                       demand_reference, posted_at, attempt_count
                from inbox_event
                where status = 'PENDING' and next_attempt_at <= ?
                order by next_attempt_at, received_at, id
                for update skip locked limit 1
                """, (rs, n) -> event(rs), Timestamp.from(clock.instant())).stream().findFirst().orElse(null);
        if (event == null) return false;
        long started = System.nanoTime();
        try {
            process(event, started);
            return true;
        } catch (RuntimeException exception) {
            throw new ProcessingFailure(event.id(), exception);
        }
    }

    private void process(Event event, long started) {
        validatePersisted(event);
        String factHash = factHash(event);
        UUID receiptId = UUID.randomUUID();
        int created = jdbc.update("""
                insert into receipt (id, source, bank_receipt_id, project_id, amount_paise, currency,
                                     demand_reference, posted_at, recorded_at, fact_hash)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (source, bank_receipt_id) do nothing
                """, receiptId, event.source(), event.bankReceiptId(), event.projectId(), event.amount(), event.currency(),
                event.reference(), Timestamp.from(event.postedAt()), Timestamp.from(clock.instant()), factHash);
        Receipt receipt = jdbc.query("""
                select id, project_id, amount_paise, currency, demand_reference, posted_at, fact_hash
                from receipt where source = ? and bank_receipt_id = ? for update
                """, (rs, n) -> receipt(rs), event.source(), event.bankReceiptId()).stream().findFirst().orElseThrow();
        if (created == 0) {
            if (!factHash.equals(receipt.factHash())) {
                UUID exceptionId = upsertException("BANK_RECORD_CONFLICT", receipt, event, "FACTS_CHANGED");
                finish(event.id(), receipt.id(), "CONFLICT");
                logCommitted(event, receipt.id(), null, exceptionId, "CONFLICT", 0, started);
                return;
            }
            // A duplicate delivery links to the original result; it intentionally never rematches it.
            finish(event.id(), receipt.id(), "PROCESSED");
            logCommitted(event, receipt.id(), null, null, "DUPLICATE", 0, started);
            return;
        }
        jdbc.update("""
                insert into financial_entry (kind, receipt_id, amount_paise, actor_id, reason, inbox_event_id)
                values ('RECEIPT', ?, ?, ?, 'BANK_RECEIPT_ACCEPTED', ?)
                """, receipt.id(), receipt.amount(), SYSTEM_ACTOR, event.id());

        long allocated = 0;
        UUID matchingDemandId = null;
        String residualReason;
        if (event.reference() == null) {
            residualReason = "MISSING_REFERENCE";
        } else {
            Demand demand = jdbc.query("""
                    select id, project_id, amount_paise from demand
                    where project_id = ? and reference = ? for update
                    """, (rs, n) -> demand(rs), receipt.projectId(), event.reference()).stream().findFirst().orElse(null);
            if (demand == null) {
                residualReason = "UNKNOWN_REFERENCE";
            } else {
                matchingDemandId = demand.id();
                PaymentAllocationService.Receipt lockedReceipt = allocationService.lockReceipt(receipt.id(), receipt.projectId());
                PaymentAllocationService.Demand lockedDemand = allocationService.lockDemand(demand.id(), receipt.projectId());
                PaymentAllocationService.Allocation applied = allocationService.allocate(lockedReceipt, lockedDemand,
                        Long.MAX_VALUE, SYSTEM_ACTOR, "AUTOMATIC_EXACT_REFERENCE", event.id(), false);
                allocated = applied.amount();
                residualReason = allocated == receipt.amount() ? null : "EXCESS_PAYMENT";
            }
        }
        UUID exceptionId = residualReason == null ? null : upsertException("UNALLOCATED_FUNDS", receipt, event, residualReason);
        finish(event.id(), receipt.id(), "PROCESSED");
        logCommitted(event, receipt.id(), matchingDemandId, exceptionId, "PROCESSED", allocated, started);
    }

    private void logCommitted(Event event, UUID receiptId, UUID demandId, UUID exceptionId, String outcome,
                              long allocated, long started) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                LOG.info("Receipt processing committed inboxEventId={} receiptId={} demandId={} exceptionId={} outcome={} allocatedPaise={} attemptCount={} durationMs={}",
                        event.id(), receiptId, demandId, exceptionId, outcome, allocated, event.attemptCount() + 1,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        });
    }

    private void finish(UUID eventId, UUID receiptId, String status) {
        int changed = jdbc.update("""
                update inbox_event set receipt_id = ?, status = ?, processed_at = ?, last_error_code = null
                where id = ? and status = 'PENDING'
                """, receiptId, status, Timestamp.from(clock.instant()), eventId);
        if (changed != 1) throw new IllegalStateException("inbox terminal update failed");
    }

    private UUID upsertException(String type, Receipt receipt, Event event, String reason) {
        String key = "BANK_RECORD_CONFLICT".equals(type)
                ? type + ":" + event.source() + ":" + event.bankReceiptId()
                : type + ":" + receipt.id();
        UUID exceptionId = jdbc.queryForObject("""
                insert into exception_case (type, project_id, receipt_id, source, bank_receipt_id, dedupe_key, reason_code)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (dedupe_key) do update set last_seen_at = now(), reason_code = excluded.reason_code,
                    status = 'OPEN', resolved_at = null
                returning id
                """, UUID.class, type, receipt.projectId(), receipt.id(), event.source(), event.bankReceiptId(), key, reason);
        jdbc.update("""
                insert into audit_event (actor_id, action, entity_type, entity_id, reason, request_id)
                values (?, 'EXCEPTION_OBSERVED', 'RECEIPT', ?, ?, ?)
                """, SYSTEM_ACTOR, receipt.id(), reason, "inbox-" + event.id());
        return exceptionId;
    }

    private static void validatePersisted(Event event) {
        if (event.id() == null || event.projectId() == null || event.accountReference() == null
                || event.accountReference().isBlank() || event.source() == null || event.source().isBlank()
                || event.bankReceiptId() == null
                || event.bankReceiptId().isBlank() || event.amount() <= 0 || !"INR".equals(event.currency())
                || event.postedAt() == null || (event.reference() != null && (!event.reference().equals(event.reference().strip()) || event.reference().isBlank()))) {
            throw new InvalidPersistedEvent();
        }
    }
    private static String factHash(Event event) {
        return sha256(event.source() + "\n" + event.projectId() + "\n" + event.accountReference() + "\n" + event.bankReceiptId()
                + "\n" + event.amount() + "\n" + event.currency() + "\n" + (event.reference() == null ? "" : event.reference()) + "\n" + event.postedAt());
    }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static Event event(ResultSet rs) throws SQLException { return new Event(rs.getObject("id", UUID.class), rs.getString("source"), rs.getObject("project_id", UUID.class), rs.getString("account_reference"), rs.getString("bank_receipt_id"), rs.getLong("amount_paise"), rs.getString("currency"), rs.getString("demand_reference"), rs.getTimestamp("posted_at").toInstant(), rs.getInt("attempt_count")); }
    private static Receipt receipt(ResultSet rs) throws SQLException { return new Receipt(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getLong("amount_paise"), rs.getString("currency"), rs.getString("demand_reference"), rs.getTimestamp("posted_at").toInstant(), rs.getString("fact_hash")); }
    private static Demand demand(ResultSet rs) throws SQLException { return new Demand(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getLong("amount_paise")); }
    private record Event(UUID id, String source, UUID projectId, String accountReference, String bankReceiptId, long amount, String currency, String reference, Instant postedAt, int attemptCount) { }
    private record Receipt(UUID id, UUID projectId, long amount, String currency, String reference, Instant postedAt, String factHash) { }
    private record Demand(UUID id, UUID projectId, long amount) { }
    public static final class InvalidPersistedEvent extends RuntimeException {
        InvalidPersistedEvent() { super("invalid durable inbox event"); }
    }
    public static final class ProcessingFailure extends RuntimeException { private final UUID eventId; ProcessingFailure(UUID eventId, RuntimeException cause) { super(cause); this.eventId = eventId; } public UUID eventId() { return eventId; } }
}
