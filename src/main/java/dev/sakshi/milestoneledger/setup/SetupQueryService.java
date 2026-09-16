package dev.sakshi.milestoneledger.setup;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import dev.sakshi.milestoneledger.shared.web.ApiException;
import dev.sakshi.milestoneledger.setup.SetupResponses.ClientResponse;
import dev.sakshi.milestoneledger.setup.SetupResponses.CollectionResponse;
import dev.sakshi.milestoneledger.setup.SetupResponses.MilestoneResponse;
import dev.sakshi.milestoneledger.setup.SetupResponses.ProjectResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SetupQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private final JdbcTemplate jdbcTemplate;
    private final SetupProperties properties;

    public SetupQueryService(JdbcTemplate jdbcTemplate, SetupProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public CollectionResponse<ProjectResponse> projects(Integer requestedLimit, String cursor) {
        int limit = limit(requestedLimit);
        Cursor decoded = decode(cursor);
        List<ProjectRow> rows = jdbcTemplate.query("""
                select p.id, p.code, p.name, p.currency, p.created_at, c.id as client_id, c.name as client_name
                from project p join client c on c.id = p.client_id
                where p.id = ?
                  and (?::timestamptz is null or (p.created_at, p.id) > (?::timestamptz, ?::uuid))
                order by p.created_at, p.id limit ?
                """, (resultSet, rowNumber) -> new ProjectRow(
                resultSet.getObject("id", UUID.class), resultSet.getString("code"), resultSet.getString("name"),
                resultSet.getString("currency"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getObject("client_id", UUID.class), resultSet.getString("client_name")),
                properties.projectId(), decoded == null ? null : decoded.timestamp().toString(),
                decoded == null ? null : decoded.timestamp().toString(), decoded == null ? null : decoded.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<ProjectRow> page = more ? rows.subList(0, limit) : rows;
        List<ProjectResponse> items = page.stream().map(row -> new ProjectResponse(row.id(), row.code(), row.name(),
                row.currency(), new ClientResponse(row.clientId(), row.clientName()))).toList();
        return new CollectionResponse<>(items, more ? encode(page.getLast().createdAt(), page.getLast().id()) : null);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public CollectionResponse<MilestoneResponse> milestones(UUID projectId, Integer requestedLimit, String cursor) {
        if (!properties.projectId().equals(projectId)) {
            throw notFound();
        }
        int limit = limit(requestedLimit);
        Cursor decoded = decode(cursor);
        List<MilestoneRow> rows = jdbcTemplate.query("""
                select id, project_id, sequence_number, name, certified_amount_paise, certification_reference,
                       certified_at, certified_by, created_at
                from milestone
                where project_id = ?
                  and (?::timestamptz is null or (created_at, id) > (?::timestamptz, ?::uuid))
                order by created_at, id limit ?
                """, (resultSet, rowNumber) -> milestone(resultSet), projectId,
                decoded == null ? null : decoded.timestamp().toString(), decoded == null ? null : decoded.timestamp().toString(),
                decoded == null ? null : decoded.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<MilestoneRow> page = more ? rows.subList(0, limit) : rows;
        List<MilestoneResponse> items = page.stream().map(row -> new MilestoneResponse(row.id(), row.projectId(),
                row.sequence(), row.name(), row.certifiedAt() == null ? "SCHEDULED" : "CERTIFIED",
                row.certifiedAmountPaise() == null ? null : row.certifiedAmountPaise().toString(), row.reference(),
                row.certifiedAt(), row.certifiedBy())).toList();
        return new CollectionResponse<>(items, more ? encode(page.getLast().createdAt(), page.getLast().id()) : null);
    }

    private static MilestoneRow milestone(ResultSet rs) throws SQLException {
        var certifiedAt = rs.getTimestamp("certified_at");
        return new MilestoneRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getInt("sequence_number"), rs.getString("name"), rs.getObject("certified_amount_paise", Long.class),
                rs.getString("certification_reference"), certifiedAt == null ? null : certifiedAt.toInstant(),
                rs.getObject("certified_by", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    private static int limit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
        }
        return limit;
    }

    private static Cursor decode(String value) {
        if (value == null) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
        }
    }

    private static String encode(Instant timestamp, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((timestamp + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    private record Cursor(Instant timestamp, UUID id) { }
    private record ProjectRow(UUID id, String code, String name, String currency, Instant createdAt, UUID clientId, String clientName) { }
    private record MilestoneRow(UUID id, UUID projectId, int sequence, String name, Long certifiedAmountPaise,
                                String reference, Instant certifiedAt, UUID certifiedBy, Instant createdAt) { }
}
