package dev.sakshi.milestoneledger.payments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentResponses {
    private PaymentResponses() { }
    public record Receipt(UUID id, String source, String bankReceiptId, UUID projectId, String amountPaise,
                          String currency, String demandReference, Instant postedAt, Instant recordedAt,
                          String allocatedPaise, String unallocatedPaise, String status) { }
    public record Entry(UUID id, String kind, UUID receiptId, UUID demandId, String amountPaise,
                        UUID inboxEventId, String reason, Instant createdAt) { }
    public record ExceptionCase(UUID id, String type, UUID receiptId, String source, String bankReceiptId,
                                String reasonCode, String status, String residualAmountPaise,
                                Instant firstSeenAt, Instant lastSeenAt, List<String> supportedActions) { }
}
