package dev.sakshi.milestoneledger.setup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SetupResponses {
    private SetupResponses() {
    }

    public record CollectionResponse<T>(List<T> items, String nextCursor) {
    }

    public record ClientResponse(UUID id, String name) {
    }

    public record ProjectResponse(UUID id, String code, String name, String currency, ClientResponse client) {
    }

    public record MilestoneResponse(
            UUID id,
            UUID projectId,
            int sequence,
            String name,
            String status,
            String certifiedAmountPaise,
            String certificationReference,
            Instant certifiedAt,
            UUID certifiedBy) {
    }
}
