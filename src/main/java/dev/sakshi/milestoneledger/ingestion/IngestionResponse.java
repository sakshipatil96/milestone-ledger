package dev.sakshi.milestoneledger.ingestion;

import java.time.Instant;
import java.util.UUID;

public final class IngestionResponse {
    private IngestionResponse() {
    }

    public record Accepted(UUID ingestionEventId, String status, boolean duplicate) {
    }

    public record Detail(UUID id, String source, String status, UUID projectId, String accountReference,
                         String bankReceiptId, String amountPaise, String currency, String demandReference,
                         Instant postedAt, int attemptCount, Instant receivedAt, Instant nextAttemptAt,
                         String lastErrorCode) {
    }
}
