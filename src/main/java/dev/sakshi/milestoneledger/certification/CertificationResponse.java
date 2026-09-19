package dev.sakshi.milestoneledger.certification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CertificationResponse(
        UUID milestoneId,
        String status,
        String certificationReference,
        Instant certifiedAt,
        DemandResponse demand) {
    public record DemandResponse(
            UUID id,
            UUID milestoneId,
            UUID projectId,
            String reference,
            String amountPaise,
            String allocatedPaise,
            String outstandingPaise,
            String currency,
            String status,
            LocalDate dueDate) {
    }
}
