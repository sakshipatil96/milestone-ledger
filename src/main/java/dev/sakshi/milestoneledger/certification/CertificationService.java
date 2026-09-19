package dev.sakshi.milestoneledger.certification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.sakshi.milestoneledger.setup.SetupProperties;
import dev.sakshi.milestoneledger.shared.validation.ValidationRules;
import dev.sakshi.milestoneledger.shared.web.ApiException;
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
public class CertificationService {
    private static final Logger LOG = LoggerFactory.getLogger(CertificationService.class);
    private static final String OPERATION = "MILESTONE_CERTIFICATION";
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SetupProperties setupProperties;
    private final Clock clock;

    public CertificationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, SetupProperties setupProperties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.setupProperties = setupProperties;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasRole('CERTIFIER')")
    public CertificationResult certify(UUID milestoneId, String idempotencyKey, byte[] body, String requestId) {
        String key = idempotencyKey(idempotencyKey);
        CertificationRequest request = request(body);
        long amount = ValidationRules.positivePaise(request.approvedAmountPaise());
        String reference = reference(request.certificationReference());
        requireInr(request.currency());
        UUID actorId = activeCertifier();
        String requestHash = hash(milestoneId + "\n" + amount + "\n" + reference + "\nINR\n"
                + (request.dueDate() == null ? "" : request.dueDate()));

        int inserted = jdbcTemplate.update("""
                insert into idempotency_request (actor_id, operation, idempotency_key, request_hash)
                values (?, ?, ?, ?)
                on conflict (actor_id, operation, idempotency_key) do nothing
                """, actorId, OPERATION, key, requestHash);
        if (inserted == 0) {
            IdempotencyRow existing = jdbcTemplate.queryForObject("""
                    select request_hash, response_status, response_body::text as response_body
                    from idempotency_request
                    where actor_id = ? and operation = ? and idempotency_key = ?
                    for update
                    """, (rs, rowNumber) -> new IdempotencyRow(rs.getString("request_hash"),
                    rs.getObject("response_status", Integer.class), rs.getString("response_body")), actorId, OPERATION, key);
            if (!requestHash.equals(existing.requestHash())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused with a different request.");
            }
            if (existing.status() == null || existing.body() == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                        "A required dependency is unavailable.");
            }
            try {
                CertificationResponse replay = objectMapper.readValue(existing.body(), CertificationResponse.class);
                LOG.info("Certification replayed milestoneId={} demandId={} requestId={}", milestoneId,
                        replay.demand().id(), requestId);
                return new CertificationResult(HttpStatus.valueOf(existing.status()), replay, true);
            } catch (Exception exception) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.");
            }
        }

        MilestoneRow milestone = jdbcTemplate.query("""
                select id, project_id, certified_at
                from milestone
                where id = ? and project_id = ?
                for update
                """, (rs, rowNumber) -> new MilestoneRow(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), timestamp(rs, "certified_at")), milestoneId, setupProperties.projectId())
                .stream().findFirst().orElseThrow(CertificationService::notFound);
        if (milestone.certifiedAt() != null) {
            throw conflict("MILESTONE_ALREADY_CERTIFIED", "Milestone has already been certified.");
        }
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from milestone where certification_reference = ?)", Boolean.class, reference))) {
            throw conflict("CERTIFICATION_REFERENCE_CONFLICT", "Certification reference is already in use.");
        }

        Instant certifiedAt = clock.instant();
        jdbcTemplate.update("""
                update milestone
                set certified_amount_paise = ?, certification_reference = ?, certified_at = ?, certified_by = ?
                where id = ?
                """, amount, reference, Timestamp.from(certifiedAt), actorId, milestone.id());
        UUID demandId = UUID.randomUUID();
        String demandReference = "DEM-" + demandId;
        jdbcTemplate.update("""
                insert into demand (id, milestone_id, project_id, reference, amount_paise, due_date)
                values (?, ?, ?, ?, ?, ?)
                """, demandId, milestone.id(), milestone.projectId(), demandReference, amount, request.dueDate());
        jdbcTemplate.update("""
                insert into audit_event (actor_id, action, entity_type, entity_id, reason, request_id)
                values (?, 'MILESTONE_CERTIFIED', 'MILESTONE', ?, ?, ?)
                """, actorId, milestone.id(), reference, requestId);

        CertificationResponse response = new CertificationResponse(milestone.id(), "CERTIFIED", reference, certifiedAt,
                persistedDemand(demandId));
        try {
            jdbcTemplate.update("""
                    update idempotency_request set response_status = ?, response_body = ?::jsonb where actor_id = ?
                    and operation = ? and idempotency_key = ?
                    """, HttpStatus.CREATED.value(), objectMapper.writeValueAsString(response), actorId, OPERATION, key);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOG.info("Certification committed milestoneId={} demandId={} requestId={}", milestone.id(), demandId, requestId);
            }
        });
        return new CertificationResult(HttpStatus.CREATED, response, false);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public CertificationResponse.DemandResponse demand(UUID demandId) {
        return jdbcTemplate.query("""
                select id, milestone_id, project_id, reference, amount_paise, due_date
                from demand where id = ? and project_id = ?
                """, (rs, rowNumber) -> demand(rs), demandId, setupProperties.projectId()).stream().findFirst()
                .orElseThrow(CertificationService::notFound);
    }

    private CertificationResponse.DemandResponse persistedDemand(UUID demandId) {
        return jdbcTemplate.queryForObject("""
                select id, milestone_id, project_id, reference, amount_paise, due_date from demand where id = ?
                """, (rs, rowNumber) -> demand(rs), demandId);
    }

    private static CertificationResponse.DemandResponse demand(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_paise");
        return new CertificationResponse.DemandResponse(rs.getObject("id", UUID.class), rs.getObject("milestone_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("reference"), Long.toString(amount), "0",
                Long.toString(amount), "INR", "OPEN", rs.getObject("due_date", LocalDate.class));
    }

    private UUID activeCertifier() {
        String subject = SecurityContextHolder.getContext().getAuthentication().getName();
        return jdbcTemplate.query("""
                select id from app_actor where external_subject = ? and role = 'CERTIFIER' and active = true
                """, (rs, rowNumber) -> rs.getObject("id", UUID.class), subject).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied."));
    }

    private CertificationRequest request(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            requireObjectWithFields(root, Set.of("approvedAmountPaise", "currency", "certificationReference", "dueDate"),
                    Set.of("approvedAmountPaise", "currency", "certificationReference"));
            String amount = text(root, "approvedAmountPaise", true);
            String currency = text(root, "currency", true);
            String reference = text(root, "certificationReference", true);
            LocalDate dueDate = root.has("dueDate") && !root.get("dueDate").isNull()
                    ? LocalDate.parse(text(root, "dueDate", true)) : null;
            return new CertificationRequest(amount, currency, reference, dueDate);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static void requireObjectWithFields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        actual.addAll(node.propertyNames());
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) throw invalid();
    }

    private static String text(JsonNode node, String name, boolean required) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            if (required) throw invalid();
            return null;
        }
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String idempotencyKey(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        }
        return value;
    }

    private static String reference(String value) {
        String checked = ValidationRules.exactReference(value);
        if (checked.length() > 100) throw invalid();
        return checked;
    }

    private static void requireInr(String currency) {
        if (!"INR".equals(currency)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CURRENCY", "Currency must be INR.");
        }
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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

    public record CertificationResult(HttpStatus status, CertificationResponse response, boolean replayed) {
    }

    private record IdempotencyRow(String requestHash, Integer status, String body) {
    }
    private record MilestoneRow(UUID id, UUID projectId, Instant certifiedAt) {
    }
}
