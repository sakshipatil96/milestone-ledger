package dev.sakshi.milestoneledger.payments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.sakshi.milestoneledger.setup.SetupProperties;
import dev.sakshi.milestoneledger.shared.web.ApiException;
import dev.sakshi.milestoneledger.shared.web.KeysetPage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentQueryService {
    private final JdbcTemplate jdbc;
    private final SetupProperties setup;
    public PaymentQueryService(JdbcTemplate jdbc, SetupProperties setup) { this.jdbc = jdbc; this.setup = setup; }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public PaymentResponses.Receipt receipt(UUID id) {
        return jdbc.query("""
                select r.id, r.source, r.bank_receipt_id, r.project_id, r.amount_paise, r.currency, r.demand_reference,
                       r.posted_at, r.recorded_at,
                       coalesce((select sum(e.amount_paise) from financial_entry e where e.receipt_id = r.id
                                 and e.kind in ('ALLOCATION', 'ALLOCATION_REVERSAL')), 0) as allocated
                from receipt r where r.id = ? and r.project_id = ?
                """, (rs, n) -> receipt(rs), id, setup.projectId()).stream().findFirst().orElseThrow(PaymentQueryService::notFound);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public KeysetPage.Response<PaymentResponses.Entry> entries(UUID receiptId, UUID demandId, Integer limit, String cursor) {
        if ((receiptId == null) == (demandId == null)) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Specify exactly one receiptId or demandId.");
        int pageSize = KeysetPage.limit(limit);
        String context = "entries:" + (receiptId == null ? "demand:" + demandId : "receipt:" + receiptId);
        KeysetPage.Cursor decoded = KeysetPage.decode(cursor, context);
        String sql = receiptId != null ? """
                select e.id, e.kind, e.receipt_id, e.demand_id, e.amount_paise, e.inbox_event_id, e.reason, e.created_at
                from financial_entry e join receipt r on r.id = e.receipt_id
                where e.receipt_id = ? and r.project_id = ?
                  and (?::timestamptz is null or (e.created_at, e.id) > (?::timestamptz, ?::uuid))
                order by e.created_at, e.id limit ?
                """ : """
                select e.id, e.kind, e.receipt_id, e.demand_id, e.amount_paise, e.inbox_event_id, e.reason, e.created_at
                from financial_entry e join demand d on d.id = e.demand_id
                where e.demand_id = ? and d.project_id = ?
                  and (?::timestamptz is null or (e.created_at, e.id) > (?::timestamptz, ?::uuid))
                order by e.created_at, e.id limit ?
                """;
        List<PaymentResponses.Entry> rows = jdbc.query(sql, (rs, n) -> entry(rs), receiptId != null ? receiptId : demandId,
                setup.projectId(), decoded == null ? null : decoded.timestamp().toString(),
                decoded == null ? null : decoded.timestamp().toString(), decoded == null ? null : decoded.id(), pageSize + 1);
        return KeysetPage.response(rows, pageSize, context, PaymentResponses.Entry::createdAt, PaymentResponses.Entry::id);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public KeysetPage.Response<PaymentResponses.ExceptionCase> exceptions(String status, Integer limit, String cursor) {
        if (status != null && !status.matches("OPEN|RESOLVED")) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
        int pageSize = KeysetPage.limit(limit);
        String context = "exceptions:" + (status == null ? "ALL" : status);
        KeysetPage.Cursor decoded = KeysetPage.decode(cursor, context);
        String where = status == null ? "" : " and e.status = ?";
        Object[] args = status == null
                ? new Object[] { setup.projectId(), decoded == null ? null : decoded.timestamp().toString(),
                    decoded == null ? null : decoded.timestamp().toString(), decoded == null ? null : decoded.id(), pageSize + 1 }
                : new Object[] { setup.projectId(), status, decoded == null ? null : decoded.timestamp().toString(),
                    decoded == null ? null : decoded.timestamp().toString(), decoded == null ? null : decoded.id(), pageSize + 1 };
        List<PaymentResponses.ExceptionCase> rows = jdbc.query("""
                select e.id, e.type, e.receipt_id, e.source, e.bank_receipt_id, e.reason_code, e.status,
                       e.first_seen_at, e.last_seen_at,
                       coalesce(r.amount_paise - (select coalesce(sum(f.amount_paise), 0) from financial_entry f
                           where f.receipt_id = r.id and f.kind in ('ALLOCATION', 'ALLOCATION_REVERSAL')), 0) as residual
                from exception_case e left join receipt r on r.id = e.receipt_id
                where e.project_id = ?""" + where + """
                 and (?::timestamptz is null or (e.first_seen_at, e.id) > (?::timestamptz, ?::uuid))
                 order by e.first_seen_at, e.id limit ?
                """, (rs, n) -> exception(rs), args);
        return KeysetPage.response(rows, pageSize, context, PaymentResponses.ExceptionCase::firstSeenAt,
                PaymentResponses.ExceptionCase::id);
    }

    private static PaymentResponses.Receipt receipt(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_paise"), allocated = exactAggregate(rs.getBigDecimal("allocated"));
        if (allocated < 0 || allocated > amount) throw new IllegalStateException("invalid receipt ledger balance");
        String status = allocated == 0 ? "UNALLOCATED" : allocated == amount ? "ALLOCATED" : "PARTIALLY_ALLOCATED";
        return new PaymentResponses.Receipt(rs.getObject("id", UUID.class), rs.getString("source"), rs.getString("bank_receipt_id"), rs.getObject("project_id", UUID.class), Long.toString(amount), rs.getString("currency"), rs.getString("demand_reference"), rs.getTimestamp("posted_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(), Long.toString(allocated), Long.toString(amount - allocated), status);
    }
    private static PaymentResponses.Entry entry(ResultSet rs) throws SQLException { return new PaymentResponses.Entry(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("receipt_id", UUID.class), rs.getObject("demand_id", UUID.class), Long.toString(rs.getLong("amount_paise")), rs.getObject("inbox_event_id", UUID.class), rs.getString("reason"), rs.getTimestamp("created_at").toInstant()); }
    private static PaymentResponses.ExceptionCase exception(ResultSet rs) throws SQLException {
        java.math.BigDecimal residual = rs.getBigDecimal("residual");
        return new PaymentResponses.ExceptionCase(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getObject("receipt_id", UUID.class), rs.getString("source"), rs.getString("bank_receipt_id"),
                rs.getString("reason_code"), rs.getString("status"),
                residual == null ? null : Long.toString(exactAggregate(residual)),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(), List.of());
    }
    private static long exactAggregate(java.math.BigDecimal value) {
        try { return value.longValueExact(); }
        catch (ArithmeticException exception) { throw new IllegalStateException("ledger aggregate exceeds supported range", exception); }
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found."); }
}
