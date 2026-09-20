package dev.sakshi.milestoneledger.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.sakshi.milestoneledger.certification.CertificationResponse;
import dev.sakshi.milestoneledger.setup.SetupProperties;
import dev.sakshi.milestoneledger.shared.validation.ValidationRules;
import dev.sakshi.milestoneledger.shared.web.ApiException;
import dev.sakshi.milestoneledger.shared.web.KeysetPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PaymentMutationService {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentMutationService.class);
    private static final String ALLOCATION = "MANUAL_ALLOCATION";
    private static final String NOTE = "EXCEPTION_NOTE";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SetupProperties setup;
    private final PaymentAllocationService allocations;
    private final Clock clock;

    public PaymentMutationService(JdbcTemplate jdbc, ObjectMapper json, SetupProperties setup,
                                  PaymentAllocationService allocations, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.setup = setup;
        this.allocations = allocations;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ACCOUNTS','MANAGER')")
    public PaymentMutationResponses.Allocation allocate(UUID receiptId, String idempotencyKey,
                                                         byte[] body, String requestId) {
        ManualAllocationRequest request = allocationRequest(body);
        String key = key(idempotencyKey);
        Actor actor = actor();
        long amount = ValidationRules.positivePaise(request.amountPaise());
        String requestHash = hash(receiptId + "\n" + request.demandId() + "\n" + amount + "\n" + request.reason());
        PaymentMutationResponses.Allocation replay = replayOrInsert(
                actor.id(), ALLOCATION, key, requestHash, PaymentMutationResponses.Allocation.class);
        if (replay != null) return replay;

        PaymentAllocationService.Receipt receipt = allocations.lockReceipt(receiptId, setup.projectId());
        PaymentAllocationService.Demand demand = allocations.lockDemand(request.demandId(), setup.projectId());
        PaymentAllocationService.Allocation allocation = allocations.allocate(
                receipt, demand, amount, actor.id(), request.reason(), null, true);
        allocations.updateResidualException(receipt.id(), allocation.unallocated(), actor.id(), requestId);
        jdbc.update("""
                insert into audit_event(actor_id, action, entity_type, entity_id, reason, request_id)
                values (?, 'ALLOCATION_RECORDED', 'RECEIPT', ?, ?, ?)
                """, actor.id(), receipt.id(), request.reason(), requestId);

        PaymentMutationResponses.Allocation response = new PaymentMutationResponses.Allocation(
                receipt.id(), demand.id(), Long.toString(amount), request.reason(),
                currentReceipt(receipt.id()), currentDemand(demand.id()), exceptionStatus(receipt.id()));
        complete(actor.id(), ALLOCATION, key, response);
        committed("manual allocation", receipt.id(), demand.id(), requestId);
        return response;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ACCOUNTS','MANAGER')")
    public PaymentMutationResponses.Note addNote(UUID exceptionId, String idempotencyKey,
                                                  byte[] body, String requestId) {
        String reason = noteReason(body);
        String key = key(idempotencyKey);
        Actor actor = actor();
        String requestHash = hash(exceptionId + "\n" + reason);
        PaymentMutationResponses.Note replay = replayOrInsert(
                actor.id(), NOTE, key, requestHash, PaymentMutationResponses.Note.class);
        if (replay != null) return replay;

        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from exception_case where id=? and project_id=?)
                """, Boolean.class, exceptionId, setup.projectId()))) throw notFound();

        UUID id = UUID.randomUUID();
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                insert into audit_event(id, actor_id, action, entity_type, entity_id, reason, request_id, created_at)
                values (?, ?, 'EXCEPTION_NOTE', 'EXCEPTION', ?, ?, ?, ?)
                """, id, actor.id(), exceptionId, reason, requestId, Timestamp.from(createdAt));
        PaymentMutationResponses.Note response = new PaymentMutationResponses.Note(
                id, exceptionId, actor.id(), actor.displayName(), reason, createdAt);
        complete(actor.id(), NOTE, key, response);
        committed("exception note", exceptionId, null, requestId);
        return response;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER','ACCOUNTS','MANAGER')")
    public KeysetPage.Response<PaymentMutationResponses.Note> notes(UUID exceptionId, Integer limit, String cursor) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from exception_case where id=? and project_id=?)
                """, Boolean.class, exceptionId, setup.projectId()))) throw notFound();

        int size = KeysetPage.limit(limit);
        String context = "exception-notes:" + setup.projectId() + ":" + exceptionId;
        KeysetPage.Cursor after = KeysetPage.decode(cursor, context);
        String afterTime = after == null ? null : after.timestamp().toString();
        UUID afterId = after == null ? null : after.id();
        List<PaymentMutationResponses.Note> rows = jdbc.query("""
                select a.id, a.entity_id, a.actor_id, p.display_name, a.reason, a.created_at
                from audit_event a join app_actor p on p.id=a.actor_id
                where a.action='EXCEPTION_NOTE' and a.entity_type='EXCEPTION' and a.entity_id=?
                    and (?::timestamptz is null or (a.created_at,a.id)>(?::timestamptz,?::uuid))
                order by a.created_at,a.id limit ?
                """, (rs, n) -> note(rs), exceptionId, afterTime, afterTime, afterId, size + 1);
        return KeysetPage.response(rows, size, context,
                PaymentMutationResponses.Note::createdAt, PaymentMutationResponses.Note::id);
    }

    private <T> T replayOrInsert(UUID actor, String operation, String key, String requestHash, Class<T> type) {
        int inserted = jdbc.update("""
                insert into idempotency_request(actor_id, operation, idempotency_key, request_hash)
                values (?, ?, ?, ?) on conflict(actor_id, operation, idempotency_key) do nothing
                """, actor, operation, key, requestHash);
        if (inserted == 1) return null;

        Idempotency row = jdbc.queryForObject("""
                select request_hash, response_status, response_body::text from idempotency_request
                where actor_id=? and operation=? and idempotency_key=? for update
                """, (rs, n) -> new Idempotency(rs.getString(1), rs.getObject(2, Integer.class), rs.getString(3)),
                actor, operation, key);
        if (!requestHash.equals(row.hash())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused with a different request.");
        }
        if (row.status() == null || row.body() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "A required dependency is unavailable.");
        }
        try {
            return json.readValue(row.body(), type);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "An unexpected error occurred.");
        }
    }

    private void complete(UUID actor, String operation, String key, Object response) {
        try {
            jdbc.update("""
                    update idempotency_request set response_status=201, response_body=?::jsonb
                    where actor_id=? and operation=? and idempotency_key=?
                    """, json.writeValueAsString(response), actor, operation, key);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "An unexpected error occurred.");
        }
    }

    private PaymentResponses.Receipt currentReceipt(UUID id) {
        return jdbc.query("""
                select r.id,r.source,r.bank_receipt_id,r.project_id,r.amount_paise,r.currency,
                    r.demand_reference,r.posted_at,r.recorded_at,
                    coalesce((select sum(e.amount_paise) from financial_entry e
                        where e.receipt_id=r.id and e.kind in ('ALLOCATION','ALLOCATION_REVERSAL')),0) allocated
                from receipt r where r.id=?
                """, (rs, n) -> mapReceipt(rs), id).getFirst();
    }

    private CertificationResponse.DemandResponse currentDemand(UUID id) {
        return jdbc.query("""
                select d.id,d.milestone_id,d.project_id,d.reference,d.amount_paise,d.due_date,
                    coalesce((select sum(e.amount_paise) from financial_entry e
                        where e.demand_id=d.id and e.kind in ('ALLOCATION','ALLOCATION_REVERSAL')),0) allocated
                from demand d where d.id=?
                """, (rs, n) -> mapDemand(rs), id).getFirst();
    }

    private String exceptionStatus(UUID receiptId) {
        return jdbc.query("""
                select status from exception_case where receipt_id=? and type='UNALLOCATED_FUNDS'
                """, (rs, n) -> rs.getString(1), receiptId).stream().findFirst().orElse(null);
    }

    private Actor actor() {
        String subject = SecurityContextHolder.getContext().getAuthentication().getName();
        return jdbc.query("""
                select id,display_name from app_actor
                where external_subject=? and role in ('ACCOUNTS','MANAGER') and active=true
                """, (rs, n) -> new Actor(rs.getObject(1, UUID.class), rs.getString(2)), subject)
                .stream().findFirst().orElseThrow(() -> new ApiException(
                        HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied."));
    }
    private ManualAllocationRequest allocationRequest(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            fields(root, Set.of("demandId", "amountPaise", "reason"));
            JsonNode id = root.get("demandId");
            if (id == null || !id.isTextual()) throw invalid();
            return new ManualAllocationRequest(UUID.fromString(id.textValue()),
                    text(root, "amountPaise"), reason(text(root, "reason")));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String noteReason(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            fields(root, Set.of("reason"));
            return reason(text(root, "reason"));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static void fields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        actual.addAll(node.propertyNames());
        if (!actual.equals(required)) throw invalid();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String reason(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 500) {
            throw invalid();
        }
        return value;
    }

    private static String key(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required.");
        }
        return value;
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PaymentResponses.Receipt mapReceipt(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_paise");
        long allocated = rs.getBigDecimal("allocated").longValueExact();
        return new PaymentResponses.Receipt(rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("bank_receipt_id"), rs.getObject("project_id", UUID.class),
                Long.toString(amount), rs.getString("currency"), rs.getString("demand_reference"),
                rs.getTimestamp("posted_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(),
                Long.toString(allocated), Long.toString(amount - allocated),
                allocated == 0 ? "UNALLOCATED" : allocated == amount ? "ALLOCATED" : "PARTIALLY_ALLOCATED");
    }

    private static CertificationResponse.DemandResponse mapDemand(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_paise");
        long allocated = rs.getBigDecimal("allocated").longValueExact();
        long outstanding = amount - allocated;
        return new CertificationResponse.DemandResponse(rs.getObject("id", UUID.class),
                rs.getObject("milestone_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("reference"), Long.toString(amount), Long.toString(allocated),
                Long.toString(outstanding), "INR",
                allocated == 0 ? "OPEN" : outstanding == 0 ? "SETTLED" : "PARTIALLY_PAID",
                rs.getObject("due_date", java.time.LocalDate.class));
    }

    private static PaymentMutationResponses.Note note(ResultSet rs) throws SQLException {
        return new PaymentMutationResponses.Note(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant());
    }

    private static void committed(String operation, UUID primaryId, UUID relatedId, String requestId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOG.info("{} committed primaryId={} relatedId={} requestId={}",
                        operation, primaryId, relatedId, requestId);
            }
        });
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private record Actor(UUID id, String displayName) { }
    private record Idempotency(String hash, Integer status, String body) { }
}
