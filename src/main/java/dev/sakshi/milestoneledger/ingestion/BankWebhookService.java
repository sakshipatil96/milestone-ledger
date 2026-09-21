package dev.sakshi.milestoneledger.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import dev.sakshi.milestoneledger.shared.web.KeysetPage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.sakshi.milestoneledger.shared.validation.ValidationRules;
import dev.sakshi.milestoneledger.shared.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class BankWebhookService {
    private static final Logger LOG = LoggerFactory.getLogger(BankWebhookService.class);
    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BankWebhookProperties properties;
    private final Clock clock;
    private final InboxEventStore inbox;

    public BankWebhookService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, BankWebhookProperties properties,
                              Clock clock, InboxEventStore inbox) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.inbox = inbox;
    }

    @Transactional
    public IngestionResponse.Accepted accept(String timestamp, String signature, byte[] body, String requestId) {
        verifySignature(timestamp, signature, body);
        ReceiptNotification notification = notification(body);
        String canonicalHash = sha256(notification.eventId() + "\n" + notification.bankReceiptId() + "\n"
                + notification.amountPaise() + "\n" + notification.currency() + "\n" + notification.demandReference()
                + "\n" + notification.postedAt());
        UUID id = UUID.randomUUID();
        int inserted = inbox.insert(id, properties.source(), notification.eventId(), properties.projectId(),
                properties.accountReference(), notification.bankReceiptId(), notification.amountPaise(),
                notification.currency(), notification.demandReference(), notification.postedAt(), canonicalHash, "WEBHOOK");
        if (inserted == 0) {
            InboxRow existing = jdbcTemplate.queryForObject("""
                    select id, status, project_id, account_reference, bank_receipt_id, amount_paise,
                           currency, demand_reference, posted_at
                    from inbox_event where source = ? and event_id = ? for update
                    """, (rs, rowNumber) -> new InboxRow(rs.getObject("id", UUID.class), rs.getString("status"),
                    rs.getObject("project_id", UUID.class), rs.getString("account_reference"),
                    rs.getString("bank_receipt_id"), rs.getLong("amount_paise"), rs.getString("currency"),
                    rs.getString("demand_reference"), rs.getTimestamp("posted_at").toInstant()),
                    properties.source(), notification.eventId());
            if (!properties.projectId().equals(existing.projectId())
                    || !properties.accountReference().equals(existing.accountReference())
                    || !notification.bankReceiptId().equals(existing.bankReceiptId())
                    || notification.amountPaise() != existing.amountPaise()
                    || !notification.currency().equals(existing.currency())
                    || !java.util.Objects.equals(notification.demandReference(), existing.demandReference())
                    || !normalizePostTime(notification.postedAt()).equals(existing.postedAt())) {
                throw new ApiException(HttpStatus.CONFLICT, "EVENT_ID_CONFLICT",
                        "Event ID was reused with different receipt facts.");
            }
            LOG.info("Bank inbox event replayed ingestionEventId={} status={} requestId={}", existing.id(), existing.status(), requestId);
            return new IngestionResponse.Accepted(existing.id(), existing.status(), true);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOG.info("Bank inbox event accepted ingestionEventId={} status=PENDING requestId={}", id, requestId);
            }
        });
        return new IngestionResponse.Accepted(id, "PENDING", false);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public IngestionResponse.Detail event(UUID id) {
        return jdbcTemplate.query("""
                select id, source, status, project_id, account_reference, bank_receipt_id, amount_paise, currency,
                       demand_reference, posted_at, attempt_count, received_at, next_attempt_at, last_error_code,
                       receipt_id, processed_at, origin
                from inbox_event where id = ? and project_id = ?
                """, (rs, rowNumber) -> new IngestionResponse.Detail(rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("status"), rs.getObject("project_id", UUID.class), rs.getString("account_reference"),
                rs.getString("bank_receipt_id"), Long.toString(rs.getLong("amount_paise")), rs.getString("currency"),
                rs.getString("demand_reference"), rs.getTimestamp("posted_at").toInstant(), rs.getInt("attempt_count"),
                rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getString("last_error_code"), rs.getObject("receipt_id", UUID.class), timestamp(rs, "processed_at"),
                rs.getString("origin")), id, properties.projectId()).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found."));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public KeysetPage.Response<IngestionResponse.Detail> events(String status, Integer limit, String cursor) {
        if (status != null && !status.matches("PENDING|PROCESSED|CONFLICT|FAILED")) throw invalid();
        int pageSize = KeysetPage.limit(limit);
        String context = "inbox:" + (status == null ? "ALL" : status);
        KeysetPage.Cursor decoded = KeysetPage.decode(cursor, context);
        String filter = status == null ? "" : " and status = ?";
        Object[] args = status == null
                ? new Object[] { properties.projectId(), decoded == null ? null : decoded.timestamp().toString(),
                    decoded == null ? null : decoded.timestamp().toString(), decoded == null ? null : decoded.id(), pageSize + 1 }
                : new Object[] { properties.projectId(), status, decoded == null ? null : decoded.timestamp().toString(),
                    decoded == null ? null : decoded.timestamp().toString(), decoded == null ? null : decoded.id(), pageSize + 1 };
        List<IngestionResponse.Detail> rows = jdbcTemplate.query("""
                select id, source, status, project_id, account_reference, bank_receipt_id, amount_paise, currency,
                       demand_reference, posted_at, attempt_count, received_at, next_attempt_at, last_error_code,
                       receipt_id, processed_at, origin
                from inbox_event where project_id = ?""" + filter + """
                 and (?::timestamptz is null or (received_at, id) > (?::timestamptz, ?::uuid))
                 order by received_at, id limit ?
                """, (rs, n) -> detail(rs), args);
        return KeysetPage.response(rows, pageSize, context, IngestionResponse.Detail::receivedAt, IngestionResponse.Detail::id);
    }

    @Transactional
    @PreAuthorize("hasRole('MANAGER')")
    public IngestionResponse.RetryAccepted retry(UUID eventId, String reason, String idempotencyKey, String requestId) {
        if (reason == null || reason.isBlank() || !reason.equals(reason.strip()) || reason.length() > 500) throw invalid();
        UUID actorId = activeManager();
        String operation = "INGESTION_RETRY:" + eventId;
        String requestHash = sha256(reason);
        int inserted = jdbcTemplate.update("""
                insert into idempotency_request (actor_id, operation, idempotency_key, request_hash, response_status, response_body)
                values (?, ?, ?, ?, 202, jsonb_build_object('ingestionEventId', ?::text, 'status', 'PENDING', 'duplicate', false))
                on conflict (actor_id, operation, idempotency_key) do nothing
                """, actorId, operation, idempotencyKey, requestHash, eventId);
        if (inserted == 0) {
            String existing = jdbcTemplate.queryForObject("select request_hash from idempotency_request where actor_id = ? and operation = ? and idempotency_key = ? for update", String.class, actorId, operation, idempotencyKey);
            if (!requestHash.equals(existing)) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused with a different request.");
            return new IngestionResponse.RetryAccepted(eventId, "PENDING", false);
        }
        int changed = jdbcTemplate.update("""
                update inbox_event set status = 'PENDING', attempt_count = 0, next_attempt_at = ?, last_error_code = null,
                    processed_at = null where id = ? and project_id = ? and status = 'FAILED'
                """, Timestamp.from(clock.instant()), eventId, properties.projectId());
        if (changed == 0) throw new ApiException(HttpStatus.CONFLICT, "EVENT_NOT_RETRYABLE", "Event is not failed and cannot be retried.");
        jdbcTemplate.update("""
                insert into audit_event (actor_id, action, entity_type, entity_id, reason, request_id)
                values (?, 'INGESTION_RETRIED', 'INBOX_EVENT', ?, ?, ?)
                """, actorId, eventId, reason, requestId);
        return new IngestionResponse.RetryAccepted(eventId, "PENDING", false);
    }

    private void verifySignature(String timestamp, String signature, byte[] body) {
        if (timestamp == null || !timestamp.matches("[0-9]+") || signature == null || !signature.startsWith("sha256=")) {
            throw invalidSignature();
        }
        final Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (RuntimeException exception) {
            throw invalidSignature();
        }
        if (Duration.between(signedAt, clock.instant()).abs().compareTo(SIGNATURE_TOLERANCE) > 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "STALE_SIGNATURE", "Webhook signature timestamp is stale.");
        }
        byte[] expected = hmac(timestamp.getBytes(StandardCharsets.UTF_8), body);
        byte[] supplied = signature.substring("sha256=".length()).getBytes(StandardCharsets.US_ASCII);
        byte[] expectedHex = java.util.HexFormat.of().formatHex(expected).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expectedHex, supplied)) {
            throw invalidSignature();
        }
    }

    private byte[] hmac(byte[] timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp);
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ReceiptNotification notification(byte[] body) {
        if (body == null || body.length > 64 * 1024) throw invalid();
        try {
            JsonNode root = objectMapper.readTree(body);
            requireFields(root, Set.of("eventId", "receipt"), Set.of("eventId", "receipt"));
            String eventId = identifier(text(root, "eventId"), 200);
            JsonNode receipt = root.get("receipt");
            requireFields(receipt, Set.of("bankReceiptId", "amountPaise", "currency", "demandReference", "postedAt"),
                    Set.of("bankReceiptId", "amountPaise", "currency", "postedAt"));
            String bankReceiptId = identifier(text(receipt, "bankReceiptId"), 200);
            long amount = ValidationRules.positivePaise(text(receipt, "amountPaise"));
            String currency = text(receipt, "currency");
            if (!"INR".equals(currency)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CURRENCY", "Currency must be INR.");
            }
            String reference = optionalReference(receipt.get("demandReference"));
            Instant postedAt = Instant.parse(text(receipt, "postedAt"));
            return new ReceiptNotification(eventId, bankReceiptId, amount, currency, reference, postedAt);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static void requireFields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        actual.addAll(node.propertyNames());
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) throw invalid();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String identifier(String value, int maxLength) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > maxLength) throw invalid();
        return value;
    }

    private static String optionalReference(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return identifier(value.textValue(), 100);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Instant normalizePostTime(Instant value) {
        return value.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private UUID activeManager() {
        String subject = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        return jdbcTemplate.query("select id from app_actor where external_subject = ? and role = 'MANAGER' and active = true",
                (rs, n) -> rs.getObject("id", UUID.class), subject).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied."));
    }
    private static Instant timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static IngestionResponse.Detail detail(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IngestionResponse.Detail(rs.getObject("id", UUID.class), rs.getString("source"), rs.getString("status"), rs.getObject("project_id", UUID.class), rs.getString("account_reference"), rs.getString("bank_receipt_id"), Long.toString(rs.getLong("amount_paise")), rs.getString("currency"), rs.getString("demand_reference"), rs.getTimestamp("posted_at").toInstant(), rs.getInt("attempt_count"), rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("next_attempt_at").toInstant(), rs.getString("last_error_code"), rs.getObject("receipt_id", UUID.class), timestamp(rs, "processed_at"), rs.getString("origin"));
    }

    private static ApiException invalidSignature() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature is invalid.");
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
    }

    private record ReceiptNotification(String eventId, String bankReceiptId, long amountPaise, String currency,
                                       String demandReference, Instant postedAt) {
    }
    private record InboxRow(UUID id, String status, UUID projectId, String accountReference,
                            String bankReceiptId, long amountPaise, String currency,
                            String demandReference, Instant postedAt) {
    }
}
