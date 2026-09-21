package dev.sakshi.milestoneledger.reconciliation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.sakshi.milestoneledger.ingestion.BankWebhookProperties;
import dev.sakshi.milestoneledger.shared.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final BankWebhookProperties bank;

    public ReconciliationService(JdbcTemplate jdbc, BankWebhookProperties bank) {
        this.jdbc = jdbc;
        this.bank = bank;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ACCOUNTS','MANAGER')")
    public Accepted create(UUID projectId, String key, String requestId) {
        if (projectId == null) throw invalid();
        if (!bank.projectId().equals(projectId)) throw notFound();
        if (key == null || key.isBlank() || !key.equals(key.strip()) || key.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        }
        String subject = SecurityContextHolder.getContext().getAuthentication().getName();
        UUID actor = jdbc.query("select id from app_actor where external_subject=? and role in ('ACCOUNTS','MANAGER') and active=true",
                (rs, n) -> rs.getObject(1, UUID.class), subject).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied."));
        String hash = hash(projectId.toString());
        UUID runId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into idempotency_request(actor_id, operation, idempotency_key, request_hash, response_status, response_body)
                values (?, 'RECONCILIATION_START', ?, ?, 202, jsonb_build_object('runId', ?::text, 'status', 'QUEUED'))
                on conflict (actor_id, operation, idempotency_key) do nothing
                """, actor, key, hash, runId);
        if (inserted == 0) {
            return jdbc.queryForObject("""
                    select request_hash, response_body->>'runId' from idempotency_request
                    where actor_id=? and operation='RECONCILIATION_START' and idempotency_key=? for update
                    """, (rs, n) -> {
                        if (!hash.equals(rs.getString(1))) throw new ApiException(HttpStatus.CONFLICT,
                                "IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused with a different request.");
                        return new Accepted(UUID.fromString(rs.getString(2)), "QUEUED");
                    }, actor, key);
        }
        try {
            jdbc.update("""
                    insert into reconciliation_run(id, source, project_id, account_reference, actor_id, request_id)
                    values (?, ?, ?, ?, ?, ?)
                    """, runId, bank.source(), projectId, bank.accountReference(), actor, requestId);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_ALREADY_RUNNING",
                    "A reconciliation run is already active for this project.");
        }
        jdbc.update("""
                insert into audit_event(actor_id, action, entity_type, entity_id, reason, request_id)
                values (?, 'RECONCILIATION_STARTED', 'RECONCILIATION_RUN', ?, 'REQUESTED', ?)
                """, actor, runId, requestId);
        return new Accepted(runId, "QUEUED");
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER','ACCOUNTS','MANAGER')")
    public Detail get(UUID runId) {
        Detail run = jdbc.query("""
                select id, status, phase, snapshot_id, as_of, bank_count, recovered_count,
                       conflict_count, failed_count, error_code, created_at, started_at, finished_at
                from reconciliation_run where id=? and project_id=?
                """, (rs, n) -> new Detail(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("phase"), rs.getString("snapshot_id"), time(rs, "as_of"),
                rs.getInt("bank_count"), rs.getInt("recovered_count"),
                rs.getInt("conflict_count"), rs.getInt("failed_count"), rs.getString("error_code"),
                time(rs, "created_at"), time(rs, "started_at"), time(rs, "finished_at"), List.of(), List.of(),
                !List.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED").contains(rs.getString("status"))),
                runId, bank.projectId()).stream().findFirst().orElseThrow(ReconciliationService::notFound);
        List<UUID> exceptions = jdbc.query("select exception_id from reconciliation_exception where run_id=? order by exception_id",
                (rs, n) -> rs.getObject(1, UUID.class), runId);
        List<UUID> failedEvents = jdbc.query("""
                select inbox_event_id from reconciliation_item
                where run_id=? and outcome='FAILED' order by bank_receipt_id
                """, (rs, n) -> rs.getObject(1, UUID.class), runId);
        return new Detail(run.runId(), run.status(), run.phase(), run.snapshotId(), run.asOf(),
                run.bankCount(), run.recoveredCount(), run.conflictCount(), run.failedCount(), run.errorCode(),
                run.createdAt(), run.startedAt(), run.finishedAt(), exceptions, failedEvents, run.countsProvisional());
    }

    private static Instant time(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request."); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found."); }

    public record Accepted(UUID runId, String status) { }
    public record Detail(UUID runId, String status, String phase, String snapshotId, Instant asOf,
                         int bankCount, int recoveredCount, int conflictCount,
                         int failedCount, String errorCode, Instant createdAt, Instant startedAt,
                         Instant finishedAt, List<UUID> exceptionIds, List<UUID> failedEventIds,
                         boolean countsProvisional) { }
}
