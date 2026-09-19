package dev.sakshi.milestoneledger.certification;

import java.time.LocalDate;

public record CertificationRequest(
        String approvedAmountPaise,
        String currency,
        String certificationReference,
        LocalDate dueDate) {
}
