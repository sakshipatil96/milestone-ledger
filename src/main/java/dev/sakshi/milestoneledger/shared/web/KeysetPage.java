package dev.sakshi.milestoneledger.shared.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.HttpStatus;

/** Stable time/ID pagination for the operational collections. */
public final class KeysetPage {
    private KeysetPage() { }

    public record Cursor(Instant timestamp, UUID id) { }
    public record Response<T>(List<T> items, String nextCursor) { }

    public static int limit(Integer requested) {
        int value = requested == null ? 50 : requested;
        if (value < 1 || value > 100) throw invalid();
        return value;
    }

    public static Cursor decode(String value, String context) {
        if (value == null) return null;
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !context.equals(parts[2])) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    public static <T> Response<T> response(List<T> rows, int limit, String context,
                                            Function<T, Instant> timestamp, Function<T, UUID> id) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? rows.subList(0, limit) : rows;
        T last = hasMore ? items.getLast() : null;
        String next = last == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
                (timestamp.apply(last) + "|" + id.apply(last) + "|" + context).getBytes(StandardCharsets.UTF_8));
        return new Response<>(items, next);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
    }
}
