package dev.sakshi.milestoneledger.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/** Canonical persisted receipt identity shared by webhook processing and snapshot comparison. */
public final class ReceiptFacts {
    private ReceiptFacts() { }

    public static Instant normalize(Instant value) {
        return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    public static String hash(String source, UUID projectId, String account, String receiptId,
                              long amount, String currency, String reference, Instant postedAt) {
        String canonical = source + "\n" + projectId + "\n" + account + "\n" + receiptId + "\n"
                + amount + "\n" + currency + "\n" + (reference == null ? "" : reference)
                + "\n" + normalize(postedAt);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
