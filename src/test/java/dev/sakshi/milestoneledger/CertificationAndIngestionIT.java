package dev.sakshi.milestoneledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.setup.project-id=30000000-0000-0000-0000-000000000001",
        "app.security.certifier-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
        "app.security.accounts-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
        "app.security.manager-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
        "app.bank.webhook.signing-secret=test-webhook-secret",
        "app.bank.webhook.project-id=30000000-0000-0000-0000-000000000001" })
class CertificationAndIngestionIT {
    private static final String MILESTONE_ID = "40000000-0000-0000-0000-000000000002";
    private static final String WEBHOOK_SECRET = "test-webhook-secret";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("certification_test").withUsername("test_owner").withPassword("test-owner-password")
            .withInitScript("db/test/create-runtime-role.sql");

    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", () -> "milestone_app");
        r.add("spring.datasource.password", () -> "test-app-password"); r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername); r.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @LocalServerPort int serverPort;

    @Test void certificationIsAtomicIdempotentScopedAndCsrfProtected() throws Exception {
        String body = "{\"approvedAmountPaise\":\"10000000\",\"currency\":\"INR\",\"certificationReference\":\"CERT-IT-001\",\"dueDate\":\"2026-09-30\"}";
        assertThat(post("/api/v1/milestones/" + MILESTONE_ID + "/certification", "certifier", body, "cert-1", null).statusCode()).isEqualTo(403);
        Client certifier = client("certifier");
        HttpResponse<String> created = post("/api/v1/milestones/" + MILESTONE_ID + "/certification", certifier, body, "cert-1");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"status\":\"CERTIFIED\"").contains("\"allocatedPaise\":\"0\"")
                .contains("\"outstandingPaise\":\"10000000\"").contains("DEM-");
        String demandId = created.body().replaceFirst(".*\\\"id\\\":\\\"([0-9a-f-]{36})\\\".*", "$1");
        HttpResponse<String> replay = post("/api/v1/milestones/" + MILESTONE_ID + "/certification", certifier, body, "cert-1");
        assertThat(replay.statusCode()).isEqualTo(201); assertThat(replay.body()).isEqualTo(created.body());
        assertThat(post("/api/v1/milestones/" + MILESTONE_ID + "/certification", certifier,
                body.replace("10000000", "10000001"), "cert-1").body()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(post("/api/v1/milestones/" + MILESTONE_ID + "/certification", certifier, body, "cert-2").body())
                .contains("MILESTONE_ALREADY_CERTIFIED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from demand where milestone_id = ?::uuid", Integer.class, MILESTONE_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_event where action = 'MILESTONE_CERTIFIED' and entity_id = ?::uuid",
                Integer.class, MILESTONE_ID)).isOne();
        for (String role : new String[] {"certifier", "accounts", "manager"}) {
            assertThat(get("/api/v1/demands/" + demandId, role).statusCode()).isEqualTo(200);
        }
        assertThat(post("/api/v1/milestones/40000000-0000-0000-0000-000000000001/certification", client("manager"), body,
                "manager-cannot-certify").statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> jdbcTemplate.update("update milestone set certification_reference = ? where id = ?::uuid",
                "CERT-CHANGED", MILESTONE_ID)).isInstanceOf(Exception.class);
    }

    @Test void signedWebhookIsCommittedPendingAndReplaySafe() throws Exception {
        String payload = "{\"eventId\":\"evt-it-001\",\"receipt\":{\"bankReceiptId\":\"bank-it-001\",\"amountPaise\":\"4000000\",\"currency\":\"INR\",\"demandReference\":\"DEM-UNKNOWN\",\"postedAt\":\"2026-09-16T10:00:00Z\"}}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpResponse<String> accepted = webhook(payload, timestamp, signature(timestamp, payload));
        assertThat(accepted.statusCode()).isEqualTo(202); assertThat(accepted.body()).contains("\"status\":\"PENDING\"").contains("\"duplicate\":false");
        String eventId = accepted.body().replaceFirst(".*\\\"ingestionEventId\\\":\\\"([0-9a-f-]{36})\\\".*", "$1");
        HttpResponse<String> replay = webhook(payload, timestamp, signature(timestamp, payload));
        assertThat(replay.statusCode()).isEqualTo(202); assertThat(replay.body()).contains(eventId).contains("\"duplicate\":true");
        assertThat(webhook(payload.replace("4000000", "4000001"), timestamp, signature(timestamp, payload.replace("4000000", "4000001"))).body())
                .contains("EVENT_ID_CONFLICT");
        assertThat(webhook(payload, timestamp, "sha256=bad").body()).contains("INVALID_SIGNATURE");
        assertThat(webhook(payload, Long.toString(Instant.now().minusSeconds(301).getEpochSecond()), "sha256=bad").body()).contains("STALE_SIGNATURE");
        assertThat(get("/api/v1/ingestion-events/" + eventId, "manager").body()).contains("\"status\":\"PENDING\"");
        assertThat(get("/api/v1/ingestion-events/" + eventId, "certifier").statusCode()).isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject("select count(*) from inbox_event where event_id = 'evt-it-001' and status = 'PENDING'", Integer.class)).isOne();
    }

    @Test void concurrentCertificationRequestsCannotCreateDuplicateDemands() throws Exception {
        String m1Body = "{\"approvedAmountPaise\":\"10000000\",\"currency\":\"INR\",\"certificationReference\":\"CERT-CONCURRENT-001\"}";
        List<HttpResponse<String>> sameKey = concurrentPosts(MILESTONE_ID.replace("002", "001"), m1Body,
                "concurrent-same-key", "concurrent-same-key");
        assertThat(sameKey).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(jdbcTemplate.queryForObject("select count(*) from demand where milestone_id = ?::uuid", Integer.class,
                MILESTONE_ID.replace("002", "001"))).isEqualTo(1);

        UUID thirdMilestone = scheduledMilestone();
        String differentKeyBody = "{\"approvedAmountPaise\":\"10000000\",\"currency\":\"INR\",\"certificationReference\":\"CERT-CONCURRENT-002\"}";
        List<HttpResponse<String>> differentKeys = concurrentPosts(thirdMilestone.toString(), differentKeyBody,
                "concurrent-different-key-a", "concurrent-different-key-b");
        assertThat(differentKeys.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbcTemplate.queryForObject("select count(*) from demand where milestone_id = ?", Integer.class,
                thirdMilestone)).isEqualTo(1);
    }

    @Test void certificationRollsBackEveryWriteWhenPostgresRejectsEachBoundary() throws Exception {
        assertCertificationRollback("demand", "before", "demand");
        assertCertificationRollback("audit_event", "before", "audit");
        assertCertificationRollback("audit_event", "after", "audit_after");
    }

    @Test void malformedSignedWebhookIsRejectedWithoutCreatingAnInboxEvent() throws Exception {
        String payload = "{\"eventId\":\"evt-malformed-" + UUID.randomUUID() + "\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpResponse<String> response = webhook(payload, timestamp, signature(timestamp, payload));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_ERROR");
        assertThat(jdbcTemplate.queryForObject("select count(*) from inbox_event where event_id like 'evt-malformed-%'", Integer.class))
                .isZero();
    }

    @Test void concurrentWebhookReplaysProduceOnePendingInboxEvent() throws Exception {
        String eventId = "evt-concurrent-" + UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"receipt\":{\"bankReceiptId\":\"bank-" + UUID.randomUUID()
                + "\",\"amountPaise\":\"4000000\",\"currency\":\"INR\",\"demandReference\":\"DEM-UNKNOWN\",\"postedAt\":\"2026-09-16T10:00:00Z\"}}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> webhook(payload, timestamp, signature(timestamp, payload)));
            var second = executor.submit(() -> webhook(payload, timestamp, signature(timestamp, payload)));
            List<HttpResponse<String>> responses = List.of(first.get(), second.get());
            assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(202));
            assertThat(responses.stream().map(HttpResponse::body)).anySatisfy(body -> assertThat(body).contains("\"duplicate\":false"));
            assertThat(responses.stream().map(HttpResponse::body)).anySatisfy(body -> assertThat(body).contains("\"duplicate\":true"));
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from inbox_event where event_id = ?", Integer.class, eventId)).isOne();
    }

    @Test void certificationAndPendingInboxEventSurviveAFreshApplicationContext() throws Exception {
        UUID milestoneId = scheduledMilestone();
        String certificationBody = "{\"approvedAmountPaise\":\"10000000\",\"currency\":\"INR\",\"certificationReference\":\"CERT-RESTART-"
                + UUID.randomUUID() + "\"}";
        Client certifier = client("certifier");
        String key = "restart-certification-" + UUID.randomUUID();
        HttpResponse<String> created = post("/api/v1/milestones/" + milestoneId + "/certification", certifier, certificationBody, key);
        assertThat(created.statusCode()).isEqualTo(201);

        String receiptEventId = "evt-restart-" + UUID.randomUUID();
        String receiptPayload = "{\"eventId\":\"" + receiptEventId + "\",\"receipt\":{\"bankReceiptId\":\"bank-" + UUID.randomUUID()
                + "\",\"amountPaise\":\"4000000\",\"currency\":\"INR\",\"demandReference\":\"DEM-UNKNOWN\",\"postedAt\":\"2026-09-16T10:00:00Z\"}}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpResponse<String> accepted = webhook(receiptPayload, timestamp, signature(timestamp, receiptPayload));
        assertThat(accepted.statusCode()).isEqualTo(202);
        String ingestionEventId = accepted.body().replaceFirst(".*\\\"ingestionEventId\\\":\\\"([0-9a-f-]{36})\\\".*", "$1");

        try (ConfigurableApplicationContext restarted = freshApplication()) {
            int port = ((WebServerApplicationContext) restarted).getWebServer().getPort();
            Client restartedCertifier = client(port, "certifier");
            HttpResponse<String> replay = post(port, "/api/v1/milestones/" + milestoneId + "/certification", restartedCertifier,
                    certificationBody, key);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(created.body());
            assertThat(get(port, "/api/v1/ingestion-events/" + ingestionEventId, "manager").body()).contains("\"status\":\"PENDING\"");
        }
    }

    private List<HttpResponse<String>> concurrentPosts(String milestoneId, String body, String firstKey, String secondKey) throws Exception {
        Client first = client("certifier");
        Client second = client("certifier");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var firstResponse = executor.submit(() -> post("/api/v1/milestones/" + milestoneId + "/certification", first, body, firstKey));
            var secondResponse = executor.submit(() -> post("/api/v1/milestones/" + milestoneId + "/certification", second, body, secondKey));
            return List.of(firstResponse.get(), secondResponse.get());
        }
    }

    private void assertCertificationRollback(String table, String timing, String suffix) throws Exception {
        UUID milestoneId = scheduledMilestone();
        String key = "rollback-" + suffix + "-" + UUID.randomUUID();
        String reference = "CERT-ROLLBACK-" + UUID.randomUUID();
        String trigger = "test_fail_" + suffix;
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.executeUpdate("create or replace function " + trigger + "() returns trigger language plpgsql as $$ begin raise exception 'test injected failure'; end; $$");
            statement.executeUpdate("create trigger " + trigger + " " + timing + " insert on " + table
                    + " for each row execute function " + trigger + "()");
            Client certifier = client("certifier");
            String body = "{\"approvedAmountPaise\":\"10000000\",\"currency\":\"INR\",\"certificationReference\":\"" + reference + "\"}";
            HttpResponse<String> response = post("/api/v1/milestones/" + milestoneId + "/certification", certifier, body, key);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("DEPENDENCY_UNAVAILABLE");
        } finally {
            try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
                statement.executeUpdate("drop trigger if exists " + trigger + " on " + table);
                statement.executeUpdate("drop function if exists " + trigger + "()");
            }
        }
        assertThat(jdbcTemplate.queryForObject("select certified_at is null from milestone where id = ?", Boolean.class, milestoneId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from demand where milestone_id = ?", Integer.class, milestoneId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_event where entity_id = ?", Integer.class, milestoneId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from idempotency_request where idempotency_key = ?", Integer.class, key)).isZero();
    }

    private UUID scheduledMilestone() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var statement = owner.prepareStatement("""
                insert into milestone (id, project_id, sequence_number, name)
                values (?, '30000000-0000-0000-0000-000000000001',
                    (select coalesce(max(sequence_number), 0) + 1 from milestone), 'Verification fixture')
                """)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
        return id;
    }

    private Client client(String username) throws Exception {
        return client(serverPort, username);
    }

    private Client client(int port, String username) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> token = http.send(HttpRequest.newBuilder(uri(port, "/api/v1/csrf-token"))
                .header("Authorization", basic(username)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(token.statusCode()).isEqualTo(200);
        String value = token.body().replaceFirst(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");
        return new Client(http, username, value);
    }

    private HttpResponse<String> post(String path, String username, String body, String key, String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", basic(username))
                .header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Client client, String body, String key) throws Exception {
        return post(serverPort, path, client, body, key);
    }

    private HttpResponse<String> post(int port, String path, Client client, String body, String key) throws Exception {
        return client.http().send(HttpRequest.newBuilder(uri(port, path)).header("Authorization", basic(client.username()))
                .header("Idempotency-Key", key).header("X-XSRF-TOKEN", client.csrf()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> webhook(String payload, String timestamp, String signature) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/webhooks/bank"))
                .header("Content-Type", "application/json").header("X-Bank-Timestamp", timestamp)
                .header("X-Bank-Signature", signature).POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String username) throws Exception {
        return get(serverPort, path, username);
    }

    private HttpResponse<String> get(int port, String path, String username) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(port, path)).header("Authorization", basic(username)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String signature(String timestamp, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }

    private ConfigurableApplicationContext freshApplication() {
        return new SpringApplicationBuilder(MilestoneLedgerApplication.class).web(WebApplicationType.SERVLET).properties(Map.ofEntries(
                Map.entry("server.port", "0"),
                Map.entry("DB_URL", POSTGRES.getJdbcUrl()),
                Map.entry("DB_USERNAME", "milestone_app"),
                Map.entry("DB_PASSWORD", "test-app-password"),
                Map.entry("DB_MIGRATION_URL", POSTGRES.getJdbcUrl()),
                Map.entry("DB_MIGRATION_USERNAME", "test_owner"),
                Map.entry("DB_MIGRATION_PASSWORD", "test-owner-password"),
                Map.entry("APP_SETUP_PROJECT_ID", "30000000-0000-0000-0000-000000000001"),
                Map.entry("APP_SECURITY_CERTIFIER_PASSWORD_HASH", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                Map.entry("APP_SECURITY_ACCOUNTS_PASSWORD_HASH", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                Map.entry("APP_SECURITY_MANAGER_PASSWORD_HASH", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                Map.entry("BANK_WEBHOOK_SIGNING_SECRET", WEBHOOK_SECRET),
                Map.entry("BANK_WEBHOOK_PROJECT_ID", "30000000-0000-0000-0000-000000000001"),
                Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
                Map.entry("spring.datasource.username", "milestone_app"),
                Map.entry("spring.datasource.password", "test-app-password"),
                Map.entry("spring.flyway.url", POSTGRES.getJdbcUrl()),
                Map.entry("spring.flyway.user", "test_owner"),
                Map.entry("spring.flyway.password", "test-owner-password"),
                Map.entry("app.setup.project-id", "30000000-0000-0000-0000-000000000001"),
                Map.entry("app.security.certifier-password-hash", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                Map.entry("app.security.accounts-password-hash", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                Map.entry("app.security.manager-password-hash", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                Map.entry("app.bank.webhook.signing-secret", WEBHOOK_SECRET),
                Map.entry("app.bank.webhook.project-id", "30000000-0000-0000-0000-000000000001"))).run();
    }
    private URI uri(String path) { return uri(serverPort, path); }
    private static URI uri(int port, String path) { return URI.create("http://localhost:" + port + path); }
    private static String basic(String username) { return "Basic " + Base64.getEncoder().encodeToString((username + ":password").getBytes(StandardCharsets.UTF_8)); }
    private Connection ownerConnection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "test_owner", "test-owner-password"); }
    private record Client(HttpClient http, String username, String csrf) { }
}
