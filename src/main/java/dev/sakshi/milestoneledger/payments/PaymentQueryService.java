package dev.sakshi.milestoneledger.payments;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.sakshi.milestoneledger.certification.CertificationResponse;
import dev.sakshi.milestoneledger.setup.SetupProperties;
import dev.sakshi.milestoneledger.shared.web.ApiException;
import dev.sakshi.milestoneledger.shared.web.KeysetPage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentQueryService {
    private final JdbcTemplate jdbc;
    private final SetupProperties setup;

    public PaymentQueryService(JdbcTemplate jdbc, SetupProperties setup) {
        this.jdbc = jdbc;
        this.setup = setup;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public PaymentResponses.Receipt receipt(UUID id, UUID projectId) {
        return jdbc.query(receiptSql("where r.id=? and r.project_id=?"), (rs,n)->receipt(rs), id, project(projectId))
                .stream().findFirst().orElseThrow(PaymentQueryService::notFound);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public PaymentResponses.Worklist<CertificationResponse.DemandResponse> demands(
            List<String> statuses, UUID projectId, Integer limit, String cursor) {
        UUID scope = project(projectId);
        List<String> filter = demandStatuses(statuses);
        int size = KeysetPage.limit(limit);
        String context = "demands:" + scope + ":" + String.join(",", filter);
        KeysetPage.Cursor after = KeysetPage.decode(cursor, context);
        String allocated = "coalesce((select sum(e.amount_paise) from financial_entry e "
                + "where e.demand_id=d.id and e.kind in ('ALLOCATION','ALLOCATION_REVERSAL')),0)";
        String state = "case when " + allocated + "=0 then 'OPEN' when " + allocated
                + "=d.amount_paise then 'SETTLED' else 'PARTIALLY_PAID' end";
        String statusClause = filter.isEmpty() ? "" : " and " + state + " in (" + placeholders(filter.size()) + ")";
        List<Object> args = new ArrayList<>();
        args.add(scope);
        args.add(time(after));
        args.add(time(after));
        args.add(id(after));
        args.addAll(filter);
        args.add(size + 1);
        List<TimedDemand> rows = jdbc.query("""
                select d.id,d.milestone_id,d.project_id,d.reference,d.amount_paise,d.due_date,d.created_at,
                """ + allocated + " allocated from demand d where d.project_id=? "
                + "and (?::timestamptz is null or (d.created_at,d.id)>(?::timestamptz,?::uuid))"
                + statusClause + " order by d.created_at,d.id limit ?",
                (rs, n) -> timedDemand(rs), args.toArray());
        return pageDemands(rows, size, context, scope);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public PaymentResponses.Worklist<PaymentResponses.Receipt> receipts(
            List<String> statuses, UUID projectId, Integer limit, String cursor) {
        UUID scope = project(projectId);
        List<String> filter = receiptStatuses(statuses);
        int size = KeysetPage.limit(limit);
        String context = "receipts:" + scope + ":" + String.join(",", filter);
        KeysetPage.Cursor after = KeysetPage.decode(cursor, context);
        String allocated = "coalesce((select sum(e.amount_paise) from financial_entry e "
                + "where e.receipt_id=r.id and e.kind in ('ALLOCATION','ALLOCATION_REVERSAL')),0)";
        String state = "case when " + allocated + "=0 then 'UNALLOCATED' when " + allocated
                + "=r.amount_paise then 'ALLOCATED' else 'PARTIALLY_ALLOCATED' end";
        String statusClause = filter.isEmpty() ? "" : " and " + state + " in (" + placeholders(filter.size()) + ")";
        List<Object> args = new ArrayList<>();
        args.add(scope);
        args.add(time(after));
        args.add(time(after));
        args.add(id(after));
        args.addAll(filter);
        args.add(size + 1);
        List<PaymentResponses.Receipt> rows = jdbc.query(receiptSql(
                "where r.project_id=? and (?::timestamptz is null or (r.recorded_at,r.id)>(?::timestamptz,?::uuid))")
                + statusClause + " order by r.recorded_at,r.id limit ?",
                (rs, n) -> receipt(rs), args.toArray());
        boolean more = rows.size() > size;
        List<PaymentResponses.Receipt> items = more ? rows.subList(0, size) : rows;
        String next = more ? KeysetPage.encode(items.getLast().recordedAt(), items.getLast().id(), context) : null;
        return new PaymentResponses.Worklist<>(items, next, ingestion(scope), reconciliation(scope));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public KeysetPage.Response<PaymentResponses.Entry> entries(
            UUID receiptId, UUID demandId, UUID projectId, Integer limit, String cursor) {
        if ((receiptId == null) == (demandId == null)) throw invalid();
        UUID scope = project(projectId);
        int size = KeysetPage.limit(limit);
        UUID resource = receiptId == null ? demandId : receiptId;
        String table = receiptId == null ? "demand" : "receipt";
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from " + table + " where id=? and project_id=?)",
                Boolean.class, resource, scope))) throw notFound();

        String context = "entries:" + scope + ":" + table + ":" + resource;
        KeysetPage.Cursor after = KeysetPage.decode(cursor, context);
        String clause = receiptId != null ? "e.receipt_id=? and r.project_id=?" : "e.demand_id=? and d.project_id=?";
        List<PaymentResponses.Entry> rows = jdbc.query(entrySql(clause), (rs, n) -> entry(rs),
                resource, scope, time(after), time(after), id(after), size + 1);
        return KeysetPage.response(rows, size, context, PaymentResponses.Entry::createdAt,
                PaymentResponses.Entry::id);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CERTIFIER', 'ACCOUNTS', 'MANAGER')")
    public KeysetPage.Response<PaymentResponses.ExceptionCase> exceptions(
            String status, UUID projectId, Integer limit, String cursor) {
        if (status != null && !status.matches("OPEN|RESOLVED")) throw invalid();
        UUID scope = project(projectId);
        int size = KeysetPage.limit(limit);
        String context = "exceptions:" + scope + ":" + (status == null ? "ALL" : status);
        KeysetPage.Cursor after = KeysetPage.decode(cursor, context);
        String condition = status == null ? "" : " and e.status=?";
        Object[] args = status == null
                ? new Object[] {scope, time(after), time(after), id(after), size + 1}
                : new Object[] {scope, status, time(after), time(after), id(after), size + 1};
        List<PaymentResponses.ExceptionCase> rows = jdbc.query("""
                select e.id,e.type,e.receipt_id,e.source,e.bank_receipt_id,e.reason_code,e.status,
                    e.first_seen_at,e.last_seen_at,
                    case when r.id is null then null else r.amount_paise-
                        coalesce((select sum(f.amount_paise) from financial_entry f
                            where f.receipt_id=r.id and f.kind in ('ALLOCATION','ALLOCATION_REVERSAL')),0)
                    end residual
                from exception_case e left join receipt r on r.id=e.receipt_id where e.project_id=?
                """ + condition + " and (?::timestamptz is null or "
                + "(e.first_seen_at,e.id)>(?::timestamptz,?::uuid)) order by e.first_seen_at,e.id limit ?",
                (rs, n) -> exceptionCase(rs), args);
        return KeysetPage.response(rows, size, context,
                PaymentResponses.ExceptionCase::firstSeenAt, PaymentResponses.ExceptionCase::id);
    }

    private PaymentResponses.Worklist<CertificationResponse.DemandResponse> pageDemands(
            List<TimedDemand> rows, int size, String context, UUID scope) {
        boolean more = rows.size() > size;
        List<TimedDemand> items = more ? rows.subList(0, size) : rows;
        String next = more ? KeysetPage.encode(items.getLast().createdAt(),
                items.getLast().response().id(), context) : null;
        return new PaymentResponses.Worklist<>(items.stream().map(TimedDemand::response).toList(),
                next, ingestion(scope), reconciliation(scope));
    }

    private PaymentResponses.Reconciliation reconciliation(UUID projectId) {
        return jdbc.query("""
                select id,status,as_of,finished_at,error_code,
                    (select s.as_of from reconciliation_run s where s.project_id=?
                        and s.status in ('COMPLETED','COMPLETED_WITH_ERRORS')
                        order by s.created_at desc,s.id desc limit 1) successful
                from reconciliation_run where project_id=? order by created_at desc,id desc limit 1
                """, (rs, n) -> new PaymentResponses.Reconciliation(rs.getObject("id", UUID.class),
                rs.getString("status"), timestamp(rs, "as_of"), timestamp(rs, "finished_at"),
                rs.getString("error_code"), timestamp(rs, "successful")), projectId, projectId)
                .stream().findFirst().orElse(null);
    }

    private static Instant timestamp(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private PaymentResponses.Ingestion ingestion(UUID projectId) {
        return jdbc.queryForObject("""
                select count(*) filter(where status='PENDING'),
                    count(*) filter(where status='FAILED'),
                    min(received_at) filter(where status='PENDING'), now()
                from inbox_event where project_id=?
                """, (rs, n) -> new PaymentResponses.Ingestion(rs.getLong(1), rs.getLong(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4).toInstant()), projectId);
    }

    private UUID project(UUID given) {
        UUID result = given == null ? setup.projectId() : given;
        if (!setup.projectId().equals(result)) throw notFound();
        return result;
    }

    private static List<String> demandStatuses(List<String> values) {
        return statuses(values, List.of("OPEN", "PARTIALLY_PAID", "SETTLED"));
    }

    private static List<String> receiptStatuses(List<String> values) {
        return statuses(values, List.of("UNALLOCATED", "PARTIALLY_ALLOCATED", "ALLOCATED"));
    }

    private static List<String> statuses(List<String> values, List<String> valid) {
        List<String> result = values == null ? new ArrayList<>() : new ArrayList<>(values);
        if (!valid.containsAll(result)) throw invalid();
        result.sort(Comparator.naturalOrder());
        return result.stream().distinct().toList();
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String receiptSql(String where) {
        return """
                select r.id,r.source,r.bank_receipt_id,r.project_id,r.amount_paise,r.currency,
                    r.demand_reference,r.posted_at,r.recorded_at,
                    coalesce((select sum(e.amount_paise) from financial_entry e
                        where e.receipt_id=r.id and e.kind in ('ALLOCATION','ALLOCATION_REVERSAL')),0) allocated
                from receipt r
                """ + where;
    }

    private static String entrySql(String where) {
        return """
                select e.id,e.kind,e.receipt_id,e.demand_id,e.amount_paise,e.inbox_event_id,
                    e.reason,e.actor_id,a.display_name actor_display_name,e.created_at
                from financial_entry e
                left join receipt r on r.id=e.receipt_id
                left join demand d on d.id=e.demand_id
                join app_actor a on a.id=e.actor_id
                where
                """ + where + " and (?::timestamptz is null or "
                + "(e.created_at,e.id)>(?::timestamptz,?::uuid)) order by e.created_at,e.id limit ?";
    }
    private static PaymentResponses.Receipt receipt(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_paise");
        long allocated = exact(rs.getBigDecimal("allocated"));
        if (allocated < 0 || allocated > amount) throw new IllegalStateException("invalid receipt ledger balance");
        return new PaymentResponses.Receipt(rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("bank_receipt_id"), rs.getObject("project_id", UUID.class),
                Long.toString(amount), rs.getString("currency"), rs.getString("demand_reference"),
                rs.getTimestamp("posted_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(),
                Long.toString(allocated), Long.toString(amount - allocated),
                allocated == 0 ? "UNALLOCATED" : allocated == amount ? "ALLOCATED" : "PARTIALLY_ALLOCATED");
    }

    private static TimedDemand timedDemand(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_paise");
        long allocated = exact(rs.getBigDecimal("allocated"));
        if (allocated < 0 || allocated > amount) throw new IllegalStateException("invalid demand ledger balance");
        long outstanding = amount - allocated;
        CertificationResponse.DemandResponse response = new CertificationResponse.DemandResponse(
                rs.getObject("id", UUID.class), rs.getObject("milestone_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("reference"),
                Long.toString(amount), Long.toString(allocated), Long.toString(outstanding), "INR",
                allocated == 0 ? "OPEN" : outstanding == 0 ? "SETTLED" : "PARTIALLY_PAID",
                rs.getObject("due_date", LocalDate.class));
        return new TimedDemand(response, rs.getTimestamp("created_at").toInstant());
    }

    private static PaymentResponses.Entry entry(ResultSet rs) throws SQLException {
        return new PaymentResponses.Entry(rs.getObject("id", UUID.class), rs.getString("kind"),
                rs.getObject("receipt_id", UUID.class), rs.getObject("demand_id", UUID.class),
                Long.toString(rs.getLong("amount_paise")), rs.getObject("inbox_event_id", UUID.class),
                rs.getString("reason"), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_display_name"), rs.getTimestamp("created_at").toInstant());
    }

    private PaymentResponses.ExceptionCase exceptionCase(ResultSet rs) throws SQLException {
        BigDecimal residual = rs.getBigDecimal("residual");
        String type = rs.getString("type");
        String status = rs.getString("status");
        List<String> actions = new ArrayList<>();
        if (canMutate()) {
            actions.add("ADD_NOTE");
            if ("UNALLOCATED_FUNDS".equals(type) && "OPEN".equals(status)
                    && residual != null && residual.signum() > 0) actions.add("ALLOCATE");
        }
        return new PaymentResponses.ExceptionCase(rs.getObject("id", UUID.class), type,
                rs.getObject("receipt_id", UUID.class), rs.getString("source"),
                rs.getString("bank_receipt_id"), rs.getString("reason_code"), status,
                residual == null ? null : Long.toString(exact(residual)),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(), actions);
    }

    private static long exact(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("ledger aggregate exceeds supported range", exception);
        }
    }

    private static String time(KeysetPage.Cursor cursor) {
        return cursor == null ? null : cursor.timestamp().toString();
    }

    private static UUID id(KeysetPage.Cursor cursor) {
        return cursor == null ? null : cursor.id();
    }

    private static boolean canMutate() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(
                authority -> authority.getAuthority().equals("ROLE_ACCOUNTS")
                        || authority.getAuthority().equals("ROLE_MANAGER"));
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    private record TimedDemand(CertificationResponse.DemandResponse response, Instant createdAt) { }
}
