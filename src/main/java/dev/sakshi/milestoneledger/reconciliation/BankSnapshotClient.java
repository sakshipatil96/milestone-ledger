package dev.sakshi.milestoneledger.reconciliation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import dev.sakshi.milestoneledger.shared.validation.ValidationRules;
import dev.sakshi.milestoneledger.ingestion.ReceiptFacts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class BankSnapshotClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final URI base;

    public BankSnapshotClient(ObjectMapper json, @Value("${app.bank.base-url}") String baseUrl) {
        this.json = json;
        this.base = URI.create(baseUrl);
    }

    public Page fetch(String cursor) {
        try {
            String path = "/receipts" + (cursor == null ? "" : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<LimitedBody> response = http.send(request, ignored -> new LimitedBodySubscriber());
            if (response.statusCode() == 410) throw new SnapshotFailure("SNAPSHOT_EXPIRED", false);
            if (response.statusCode() != 200) throw new SnapshotFailure("BANK_UNAVAILABLE", true);
            if (response.body().oversized()) throw new SnapshotFailure("INVALID_SNAPSHOT", false);
            JsonNode root = json.readTree(response.body().bytes());
            fields(root, Set.of("snapshotId", "asOf", "items", "nextCursor"), Set.of("snapshotId", "asOf", "items", "nextCursor"));
            String id = identifier(string(root, "snapshotId"), 200);
            Instant asOf = ReceiptFacts.normalize(Instant.parse(string(root, "asOf")));
            JsonNode items = root.get("items");
            if (!items.isArray() || items.size() > 100) throw new SnapshotFailure("INVALID_SNAPSHOT", false);
            List<Item> parsed = new ArrayList<>();
            for (JsonNode item : items) {
                fields(item, Set.of("bankReceiptId", "amountPaise", "currency", "demandReference", "postedAt"),
                        Set.of("bankReceiptId", "amountPaise", "currency", "postedAt"));
                String receiptId = identifier(string(item, "bankReceiptId"), 200);
                long amount = ValidationRules.positivePaise(string(item, "amountPaise"));
                String currency = string(item, "currency");
                if (!"INR".equals(currency)) throw new SnapshotFailure("INVALID_SNAPSHOT", false);
                JsonNode referenceNode = item.get("demandReference");
                String reference = referenceNode == null || referenceNode.isNull() ? null
                        : identifier(string(item, "demandReference"), 100);
                Instant posted = Instant.parse(string(item, "postedAt"));
                ReceiptFacts.normalize(posted); // reject instants that PostgreSQL cannot normalize safely
                if (posted.isAfter(asOf)) throw new SnapshotFailure("INVALID_SNAPSHOT", false);
                parsed.add(new Item(receiptId, amount, currency, reference, posted));
            }
            JsonNode nextNode = root.get("nextCursor");
            String next = nextNode.isNull() ? null : identifier(string(root, "nextCursor"), 500);
            return new Page(id, asOf, parsed, next);
        } catch (SnapshotFailure failure) {
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SnapshotFailure("BANK_UNAVAILABLE", true);
        } catch (java.io.IOException exception) {
            throw new SnapshotFailure("BANK_UNAVAILABLE", true);
        } catch (RuntimeException exception) {
            throw new SnapshotFailure("INVALID_SNAPSHOT", false);
        }
    }

    private static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw new SnapshotFailure("INVALID_SNAPSHOT", false);
        Set<String> actual = new HashSet<>();
        actual.addAll(node.propertyNames());
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) throw new SnapshotFailure("INVALID_SNAPSHOT", false);
    }
    private static String string(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw new SnapshotFailure("INVALID_SNAPSHOT", false);
        return value.textValue();
    }
    private static String identifier(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > max
                || value.chars().anyMatch(Character::isISOControl))
            throw new SnapshotFailure("INVALID_SNAPSHOT", false);
        return value;
    }

    private record LimitedBody(byte[] bytes, boolean oversized) { }
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<LimitedBody> {
        private final CompletableFuture<LimitedBody> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<LimitedBody> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.complete(new LimitedBody(null, true));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(new LimitedBody(bytes.toByteArray(), false)); }
    }

    public record Item(String bankReceiptId, long amount, String currency, String reference, Instant postedAt) { }
    public record Page(String snapshotId, Instant asOf, List<Item> items, String nextCursor) { }
    public static final class SnapshotFailure extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public SnapshotFailure(String code, boolean retryable) { super(code); this.code = code; this.retryable = retryable; }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}
