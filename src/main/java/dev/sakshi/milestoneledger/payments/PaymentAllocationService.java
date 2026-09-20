package dev.sakshi.milestoneledger.payments;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import dev.sakshi.milestoneledger.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shared receipt-then-demand locking and ledger mutation for all allocation paths. */
@Service
public class PaymentAllocationService {
    private final JdbcTemplate jdbc;

    public PaymentAllocationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Receipt lockReceipt(UUID receiptId, UUID projectId) {
        return jdbc.query("""
                select r.id, r.project_id, p.client_id, r.amount_paise, r.currency
                from receipt r join project p on p.id = r.project_id
                where r.id = ? and r.project_id = ? for update of r
                """, (rs, n) -> receipt(rs), receiptId, projectId).stream().findFirst()
                .orElseThrow(PaymentAllocationService::notFound);
    }

    public Demand lockDemand(UUID demandId, UUID projectId) {
        return jdbc.query("""
                select d.id, d.project_id, p.client_id, d.amount_paise, p.currency
                from demand d join project p on p.id = d.project_id
                where d.id = ? and d.project_id = ? for update of d
                """, (rs, n) -> demand(rs), demandId, projectId).stream().findFirst()
                .orElseThrow(PaymentAllocationService::notFound);
    }

    public Allocation allocate(Receipt receipt, Demand demand, long requestedAmount, UUID actorId,
                               String reason, UUID inboxEventId, boolean mustAllocateAll) {
        if (!receipt.projectId().equals(demand.projectId()) || !receipt.clientId().equals(demand.clientId())
                || !receipt.currency().equals(demand.currency())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
        }
        long receiptAvailable = receipt.amount() - allocatedForReceipt(receipt.id());
        long demandOutstanding = demand.amount() - allocatedForDemand(demand.id());
        if (receiptAvailable < 0 || demandOutstanding < 0) throw new IllegalStateException("invalid ledger balance");
        if (mustAllocateAll && requestedAmount > receiptAvailable) {
            throw conflict("ALLOCATION_EXCEEDS_UNALLOCATED", "Allocation exceeds unallocated receipt funds.");
        }
        if (mustAllocateAll && requestedAmount > demandOutstanding) {
            throw conflict("ALLOCATION_EXCEEDS_OUTSTANDING", "Allocation exceeds outstanding demand balance.");
        }
        long amount = mustAllocateAll ? requestedAmount : Math.min(receiptAvailable, demandOutstanding);
        if (amount > 0) {
            jdbc.update("""
                    insert into financial_entry (kind, receipt_id, demand_id, amount_paise, actor_id, reason, inbox_event_id)
                    values ('ALLOCATION', ?, ?, ?, ?, ?, ?)
                    """, receipt.id(), demand.id(), amount, actorId, reason, inboxEventId);
        }
        return new Allocation(amount, receiptAvailable - amount, demandOutstanding - amount);
    }

    public void updateResidualException(UUID receiptId, long unallocated, UUID actorId, String requestId) {
        if (unallocated != 0) return;
        int resolved = jdbc.update("""
                update exception_case set status = 'RESOLVED', resolved_at = now(), last_seen_at = now()
                where receipt_id = ? and type = 'UNALLOCATED_FUNDS' and status = 'OPEN'
                """, receiptId);
        if (resolved > 0) {
            jdbc.update("""
                    insert into audit_event (actor_id, action, entity_type, entity_id, reason, request_id)
                    values (?, 'RESIDUAL_FUNDS_RESOLVED', 'RECEIPT', ?, 'RECEIPT_FULLY_ALLOCATED', ?)
                    """, actorId, receiptId, requestId);
        }
    }

    private long allocatedForReceipt(UUID id) {
        return aggregate("""
                select coalesce(sum(amount_paise), 0) from financial_entry
                where receipt_id = ? and kind in ('ALLOCATION', 'ALLOCATION_REVERSAL')
                """, id);
    }

    private long allocatedForDemand(UUID id) {
        return aggregate("""
                select coalesce(sum(amount_paise), 0) from financial_entry
                where demand_id = ? and kind in ('ALLOCATION', 'ALLOCATION_REVERSAL')
                """, id);
    }

    private long aggregate(String sql, UUID id) {
        try {
            return jdbc.queryForObject(sql, BigDecimal.class, id).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("ledger aggregate exceeds supported range", exception);
        }
    }

    private static Receipt receipt(ResultSet rs) throws SQLException {
        return new Receipt(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("client_id", UUID.class), rs.getLong("amount_paise"), rs.getString("currency"));
    }

    private static Demand demand(ResultSet rs) throws SQLException {
        return new Demand(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("client_id", UUID.class), rs.getLong("amount_paise"), rs.getString("currency"));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public record Receipt(UUID id, UUID projectId, UUID clientId, long amount, String currency) { }
    public record Demand(UUID id, UUID projectId, UUID clientId, long amount, String currency) { }
    public record Allocation(long amount, long unallocated, long outstanding) { }
}
