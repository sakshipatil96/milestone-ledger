package dev.sakshi.milestoneledger.payments;

import java.time.Instant;
import java.util.UUID;
import dev.sakshi.milestoneledger.certification.CertificationResponse;

public final class PaymentMutationResponses {
    private PaymentMutationResponses() { }

    public record Allocation(UUID receiptId, UUID demandId, String amountPaise, String reason,
                             PaymentResponses.Receipt receipt, CertificationResponse.DemandResponse demand,
                             String exceptionStatus) { }
    public record Note(UUID id, UUID exceptionId, UUID actorId, String actorDisplayName,
                       String reason, Instant createdAt) { }
}
