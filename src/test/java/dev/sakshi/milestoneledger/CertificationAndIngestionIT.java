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
import java.sql.Timestamp;
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
import dev.sakshi.milestoneledger.payments.ReceiptProcessor;
import dev.sakshi.milestoneledger.payments.ReceiptFailureRecorder;
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
        "app.bank.webhook.project-id=30000000-0000-0000-0000-000000000001",
        "app.receipt-worker.initial-delay-ms=60000" })
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
    @Autowired ReceiptProcessor receiptProcessor;
    @Autowired ReceiptFailureRecorder receiptFailureRecorder;
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

    @Test void nullableReferencesAndNormalizedLegacyReplayAreValidated() throws Exception {
        String omitted = receiptPayload("evt-omitted-" + UUID.randomUUID(), "bank-omitted-" + UUID.randomUUID(), "1", null);
        acceptWebhook(omitted);
        String explicitNull = omitted.replace("\"postedAt\"", "\"demandReference\":null,\"postedAt\"")
                .replace("evt-omitted-", "evt-null-").replace("bank-omitted-", "bank-null-");
        acceptWebhook(explicitNull);
        String padded = receiptPayload("evt-padded-" + UUID.randomUUID(), "bank-padded-" + UUID.randomUUID(), "1", " DEM-INVALID ");
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        assertThat(webhook(padded, timestamp, signature(timestamp, padded)).statusCode()).isEqualTo(400);

        String legacyDelivery = "evt-legacy-" + UUID.randomUUID();
        String legacyBank = "bank-legacy-" + UUID.randomUUID();
        try (Connection owner = ownerConnection(); var insert = owner.prepareStatement("""
                insert into inbox_event (source, event_id, project_id, account_reference, bank_receipt_id,
                    amount_paise, currency, demand_reference, posted_at, canonical_hash)
                values ('demo-bank', ?, '30000000-0000-0000-0000-000000000001', 'DEMO-ACCOUNT-001',
                    ?, 1, 'INR', 'DEM-UNKNOWN', '2026-09-16T10:00:00Z', repeat('0', 64))
                """)) {
            insert.setString(1, legacyDelivery);
            insert.setString(2, legacyBank);
            insert.executeUpdate();
        }
        String legacyPayload = receiptPayload(legacyDelivery, legacyBank, "1", "DEM-UNKNOWN");
        assertThat(webhook(legacyPayload, timestamp, signature(timestamp, legacyPayload)).body()).contains("\"duplicate\":true");
        assertThat(jdbcTemplate.queryForObject("select canonical_hash from inbox_event where event_id = ?", String.class, legacyDelivery))
                .isEqualTo("0".repeat(64));
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

    @Test void receiptWorkerCreatesOneFinancialEffectAndKeepsResidualsVisible() throws Exception {
        UUID milestoneId = scheduledMilestone();
        Client certifier = client("certifier");
        String certification = "{\"approvedAmountPaise\":\"10000000\",\"currency\":\"INR\",\"certificationReference\":\"CERT-WORKER-" + UUID.randomUUID() + "\"}";
        HttpResponse<String> certified = post("/api/v1/milestones/" + milestoneId + "/certification", certifier, certification, "worker-cert-" + UUID.randomUUID());
        assertThat(certified.statusCode()).isEqualTo(201);
        String demandReference = certified.body().replaceFirst(".*\\\"reference\\\":\\\"(DEM-[0-9a-f-]{36})\\\".*", "$1");
        String receiptId = "bank-worker-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            String payload = "{\"eventId\":\"evt-worker-" + i + "-" + UUID.randomUUID() + "\",\"receipt\":{\"bankReceiptId\":\"" + receiptId
                    + "\",\"amountPaise\":\"4000000\",\"currency\":\"INR\",\"demandReference\":\"" + demandReference + "\",\"postedAt\":\"2026-09-16T10:00:00Z\"}}";
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            assertThat(webhook(payload, timestamp, signature(timestamp, payload)).statusCode()).isEqualTo(202);
        }
        drainReceiptWorker();
        assertThat(jdbcTemplate.queryForObject("select count(*) from receipt where bank_receipt_id = ?", Integer.class, receiptId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id = (select id from receipt where bank_receipt_id = ?)", Integer.class, receiptId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select coalesce(sum(amount_paise), 0) from financial_entry where demand_id = (select id from demand where reference = ?)", Long.class, demandReference)).isEqualTo(4_000_000L);

        String unknown = "{\"eventId\":\"evt-unknown-" + UUID.randomUUID() + "\",\"receipt\":{\"bankReceiptId\":\"bank-unknown-" + UUID.randomUUID()
                + "\",\"amountPaise\":\"1\",\"currency\":\"INR\",\"postedAt\":\"2026-09-16T10:00:00Z\"}}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        assertThat(webhook(unknown, timestamp, signature(timestamp, unknown)).statusCode()).isEqualTo(202);
        drainReceiptWorker();
        assertThat(jdbcTemplate.queryForObject("select count(*) from exception_case where reason_code = 'MISSING_REFERENCE' and status = 'OPEN'", Integer.class)).isPositive();
    }

    @Test void equalValueReceiptsAndConcurrentMatchingCannotOverpayDemand() throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand("5000000");
        String first = receiptPayload("evt-race-a-" + UUID.randomUUID(), "bank-race-a-" + UUID.randomUUID(), "4000000", reference);
        String second = receiptPayload("evt-race-b-" + UUID.randomUUID(), "bank-race-b-" + UUID.randomUUID(), "4000000", reference);
        acceptWebhook(first); acceptWebhook(second);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(receiptProcessor::processOneDueEvent);
            var b = executor.submit(receiptProcessor::processOneDueEvent);
            assertThat(a.get()).isTrue(); assertThat(b.get()).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from receipt where bank_receipt_id like 'bank-race-%'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select coalesce(sum(amount_paise), 0) from financial_entry where demand_id = (select id from demand where reference = ?)", Long.class, reference)).isEqualTo(5_000_000L);
        assertThat(jdbcTemplate.queryForObject("select sum(r.amount_paise - coalesce((select sum(amount_paise) from financial_entry f where f.receipt_id = r.id and f.kind = 'ALLOCATION'), 0)) from receipt r where bank_receipt_id like 'bank-race-%'", Long.class)).isEqualTo(3_000_000L);
    }

    @Test void concurrentIdenticalDeliveriesProduceOneReceiptAndOneAllocation() throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand("5000000");
        String bankId = "bank-concurrent-duplicate-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            acceptWebhook(receiptPayload("evt-concurrent-duplicate-" + i + "-" + UUID.randomUUID(), bankId, "4000000", reference));
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) results.add(executor.submit(receiptProcessor::processOneDueEvent));
            for (var result : results) assertThat(result.get()).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from receipt where bank_receipt_id = ?", Integer.class, bankId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id = (select id from receipt where bank_receipt_id = ?)", Integer.class, bankId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from inbox_event where bank_receipt_id = ? and status = 'PROCESSED'", Integer.class, bankId)).isEqualTo(5);
    }

    @Test void concurrentChangedReceiptFactsExposeConflictAndPreserveWinningMoney() throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand("5000000");
        String bankId = "bank-concurrent-conflict-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-concurrent-original-" + UUID.randomUUID(), bankId, "4000000", reference));
        acceptWebhook(receiptPayload("evt-concurrent-changed-" + UUID.randomUUID(), bankId, "4000001", reference));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(receiptProcessor::processOneDueEvent);
            var second = executor.submit(receiptProcessor::processOneDueEvent);
            assertThat(first.get()).isTrue();
            assertThat(second.get()).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from receipt where bank_receipt_id = ?", Integer.class, bankId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id = (select id from receipt where bank_receipt_id = ?)", Integer.class, bankId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from inbox_event where bank_receipt_id = ? and status = 'CONFLICT'", Integer.class, bankId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from exception_case where type = 'BANK_RECORD_CONFLICT' and bank_receipt_id = ?", Integer.class, bankId)).isOne();
    }

    @Test void changedReceiptFactsBecomeConflictWithoutChangingOriginalMoney() throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand("10000000");
        String bankReceiptId = "bank-conflict-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-conflict-original-" + UUID.randomUUID(), bankReceiptId, "4000000", reference));
        drainReceiptWorker();
        acceptWebhook(receiptPayload("evt-conflict-changed-" + UUID.randomUUID(), bankReceiptId, "4000001", reference));
        drainReceiptWorker();
        assertThat(jdbcTemplate.queryForObject("select amount_paise from receipt where bank_receipt_id = ?", Long.class, bankReceiptId)).isEqualTo(4_000_000L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id = (select id from receipt where bank_receipt_id = ?)", Integer.class, bankReceiptId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from inbox_event where bank_receipt_id = ? and status = 'CONFLICT'", Integer.class, bankReceiptId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from exception_case where type = 'BANK_RECORD_CONFLICT' and bank_receipt_id = ?", Integer.class, bankReceiptId)).isOne();
        UUID caseId = jdbcTemplate.queryForObject("select id from exception_case where type = 'BANK_RECORD_CONFLICT' and bank_receipt_id = ?", UUID.class, bankReceiptId);
        try (Connection owner = ownerConnection(); var update = owner.prepareStatement("update exception_case set status = 'RESOLVED', resolved_at = now() where id = ?")) {
            update.setObject(1, caseId); update.executeUpdate();
        }
        acceptWebhook(receiptPayload("evt-conflict-again-" + UUID.randomUUID(), bankReceiptId, "4000001", reference));
        drainReceiptWorker();
        assertThat(jdbcTemplate.queryForObject("select status from exception_case where id = ?", String.class, caseId)).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject("select count(*) from exception_case where type = 'BANK_RECORD_CONFLICT' and bank_receipt_id = ?", Integer.class, bankReceiptId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_event where action = 'EXCEPTION_OBSERVED' and entity_id = (select id from receipt where bank_receipt_id = ?)", Integer.class, bankReceiptId)).isEqualTo(2);
    }

    @Test void partialFinalAndExcessBalancesComeFromPersistedHistory() throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand("10000000");
        UUID demandId = jdbcTemplate.queryForObject("select id from demand where reference = ?", UUID.class, reference);
        String partialBank = "bank-partial-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-partial-" + UUID.randomUUID(), partialBank, "4000000", reference));
        drainReceiptWorker();
        assertThat(get("/api/v1/demands/" + demandId, "accounts").body())
                .contains("\"allocatedPaise\":\"4000000\"").contains("\"outstandingPaise\":\"6000000\"")
                .contains("\"status\":\"PARTIALLY_PAID\"");
        String finalBank = "bank-final-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-final-" + UUID.randomUUID(), finalBank, "7000000", reference));
        drainReceiptWorker();
        UUID finalReceipt = jdbcTemplate.queryForObject("select id from receipt where bank_receipt_id = ?", UUID.class, finalBank);
        assertThat(get("/api/v1/demands/" + demandId, "certifier").body())
                .contains("\"allocatedPaise\":\"10000000\"").contains("\"outstandingPaise\":\"0\"")
                .contains("\"status\":\"SETTLED\"");
        assertThat(get("/api/v1/receipts/" + finalReceipt, "manager").body())
                .contains("\"allocatedPaise\":\"6000000\"").contains("\"unallocatedPaise\":\"1000000\"");
        assertThat(get("/api/v1/financial-entries?demandId=" + demandId, "accounts").body())
                .contains("\"amountPaise\":\"4000000\"").contains("\"amountPaise\":\"6000000\"");
        assertThat(get("/api/v1/exceptions?status=OPEN", "accounts").body()).contains("EXCESS_PAYMENT");
    }

    @Test void duplicateReceiptNeverRematchesAndLateFailureCannotOverwriteSuccess() throws Exception {
        drainReceiptWorker();
        String reference = "DEM-LATE-" + UUID.randomUUID();
        String bankId = "bank-late-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-late-first-" + UUID.randomUUID(), bankId, "100", reference));
        drainReceiptWorker();
        UUID milestoneId = scheduledMilestone();
        UUID demandId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var insert = owner.prepareStatement("""
                insert into demand (id, milestone_id, project_id, reference, amount_paise)
                values (?, ?, '30000000-0000-0000-0000-000000000001', ?, 100)
                """)) {
            insert.setObject(1, demandId);
            insert.setObject(2, milestoneId);
            insert.setString(3, reference);
            insert.executeUpdate();
        }
        acceptWebhook(receiptPayload("evt-late-duplicate-" + UUID.randomUUID(), bankId, "100", reference));
        drainReceiptWorker();
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where demand_id = ?", Integer.class, demandId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id = (select id from receipt where bank_receipt_id = ?)", Integer.class, bankId)).isOne();
        UUID firstEvent = jdbcTemplate.queryForObject("select id from inbox_event where bank_receipt_id = ? order by received_at limit 1", UUID.class, bankId);
        receiptFailureRecorder.record(firstEvent);
        assertThat(jdbcTemplate.queryForObject("select status from inbox_event where id = ?", String.class, firstEvent)).isEqualTo("PROCESSED");
        assertThat(jdbcTemplate.queryForObject("select attempt_count from inbox_event where id = ?", Integer.class, firstEvent)).isZero();
    }

    @Test void failedFinancialTransactionRollsBackAndRecordsRecoverableAttempt() throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand("10000000");
        String bankReceiptId = "bank-rollback-" + UUID.randomUUID();
        String payload = receiptPayload("evt-rollback-" + UUID.randomUUID(), bankReceiptId, "4000000", reference);
        acceptWebhook(payload);
        String trigger = "test_fail_financial_entry_" + UUID.randomUUID().toString().replace('-', '_');
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.executeUpdate("create or replace function " + trigger + "() returns trigger language plpgsql as $$ begin raise exception 'test injected failure'; end; $$");
            statement.executeUpdate("create trigger " + trigger + " before insert on financial_entry for each row execute function " + trigger + "()");
            org.assertj.core.api.Assertions.assertThatThrownBy(receiptProcessor::processOneDueEvent)
                    .isInstanceOf(ReceiptProcessor.ProcessingFailure.class);
        } finally {
            try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
                statement.executeUpdate("drop trigger if exists " + trigger + " on financial_entry");
                statement.executeUpdate("drop function if exists " + trigger + "()");
            }
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from receipt where bank_receipt_id = ?", Integer.class, bankReceiptId)).isZero();
        UUID eventId = jdbcTemplate.queryForObject("select id from inbox_event where bank_receipt_id = ?", UUID.class, bankReceiptId);
        receiptFailureRecorder.record(eventId);
        assertThat(jdbcTemplate.queryForObject("select attempt_count from inbox_event where id = ?", Integer.class, eventId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select status from inbox_event where id = ?", String.class, eventId)).isEqualTo("PENDING");
    }

    @Test void everyReceiptWriteBoundaryRollsBackAsOneTransaction() throws Exception {
        drainReceiptWorker();
        List<String[]> boundaries = List.of(
                new String[] { "receipt", "insert", "true" },
                new String[] { "financial_entry", "insert", "new.kind = 'RECEIPT'" },
                new String[] { "financial_entry", "insert", "new.kind = 'ALLOCATION'" },
                new String[] { "exception_case", "insert", "true" },
                new String[] { "audit_event", "insert", "new.action = 'EXCEPTION_OBSERVED'" },
                new String[] { "inbox_event", "update", "new.status = 'PROCESSED'" });
        for (String[] boundary : boundaries) {
            String reference = certifyDemand("1");
            String bankId = "bank-boundary-" + UUID.randomUUID();
            acceptWebhook(receiptPayload("evt-boundary-" + UUID.randomUUID(), bankId, "2", reference));
            UUID eventId = jdbcTemplate.queryForObject("select id from inbox_event where bank_receipt_id = ?", UUID.class, bankId);
            String trigger = "test_boundary_" + UUID.randomUUID().toString().replace('-', '_');
            try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
                statement.executeUpdate("create function " + trigger + "() returns trigger language plpgsql as $$ begin raise exception 'test injected failure'; end; $$");
                statement.executeUpdate("create trigger " + trigger + " after " + boundary[1] + " on " + boundary[0]
                        + " for each row when (" + boundary[2] + ") execute function " + trigger + "()");
                assertThatThrownBy(receiptProcessor::processOneDueEvent).isInstanceOf(ReceiptProcessor.ProcessingFailure.class);
            } finally {
                try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
                    statement.executeUpdate("drop trigger if exists " + trigger + " on " + boundary[0]);
                    statement.executeUpdate("drop function if exists " + trigger + "()");
                }
            }
            assertThat(jdbcTemplate.queryForObject("select count(*) from receipt where bank_receipt_id = ?", Integer.class, bankId)).isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where inbox_event_id = ?", Integer.class, eventId)).isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from exception_case where bank_receipt_id = ?", Integer.class, bankId)).isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from audit_event where request_id = ?", Integer.class, "inbox-" + eventId)).isZero();
            assertThat(jdbcTemplate.queryForObject("select status from inbox_event where id = ?", String.class, eventId)).isEqualTo("PENDING");
            receiptFailureRecorder.record(eventId, true);
            assertThat(jdbcTemplate.queryForObject("select status from inbox_event where id = ?", String.class, eventId)).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject("select last_error_code from inbox_event where id = ?", String.class, eventId)).isEqualTo("INVALID_EVENT_FACTS");
        }
    }

    @Test void retryBackoffExhaustsAfterFiveFailuresWithoutBlockingOtherWork() throws Exception {
        drainReceiptWorker();
        String exhausted = "bank-exhausted-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-exhausted-" + UUID.randomUUID(), exhausted, "1", null));
        UUID exhaustedEvent = jdbcTemplate.queryForObject("select id from inbox_event where bank_receipt_id = ?", UUID.class, exhausted);
        Instant received = jdbcTemplate.queryForObject("select received_at from inbox_event where id = ?", Instant.class, exhaustedEvent);
        receiptFailureRecorder.record(exhaustedEvent);
        Instant firstDue = jdbcTemplate.queryForObject("select next_attempt_at from inbox_event where id = ?", Instant.class, exhaustedEvent);
        assertThat(firstDue).isAfterOrEqualTo(received.plusSeconds(1));
        for (int seconds : List.of(2, 4, 8)) {
            receiptFailureRecorder.record(exhaustedEvent);
            Instant due = jdbcTemplate.queryForObject("select next_attempt_at from inbox_event where id = ?", Instant.class, exhaustedEvent);
            long remainingMillis = java.time.Duration.between(Instant.now(), due).toMillis();
            assertThat(remainingMillis).isBetween(seconds * 1000L - 1000L, seconds * 1000L + 1000L);
        }
        receiptFailureRecorder.record(exhaustedEvent);
        assertThat(jdbcTemplate.queryForObject("select attempt_count from inbox_event where id = ?", Integer.class, exhaustedEvent)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("select status from inbox_event where id = ?", String.class, exhaustedEvent)).isEqualTo("FAILED");

        String independent = "bank-independent-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-independent-" + UUID.randomUUID(), independent, "1", null));
        assertThat(receiptProcessor.processOneDueEvent()).isTrue();
        assertThat(jdbcTemplate.queryForObject("select status from inbox_event where bank_receipt_id = ?", String.class, independent)).isEqualTo("PROCESSED");
    }

    @Test void managerRetryIsCsrfProtectedReasonedAndIdempotent() throws Exception {
        drainReceiptWorker();
        String bankReceiptId = "bank-manager-retry-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-manager-retry-" + UUID.randomUUID(), bankReceiptId, "1", null));
        UUID eventId = jdbcTemplate.queryForObject("select id from inbox_event where bank_receipt_id = ?", UUID.class, bankReceiptId);
        for (int i = 0; i < 5; i++) receiptFailureRecorder.record(eventId);
        Client manager = client("manager");
        String path = "/api/v1/ingestion-events/" + eventId + "/retry";
        HttpResponse<String> noCsrf = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", basic("manager")).header("Idempotency-Key", "no-csrf-" + eventId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"bank corrected delivery\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(postJson(path, client("accounts"), "{\"reason\":\"bank corrected delivery\"}",
                "accounts-retry-" + eventId).statusCode()).isEqualTo(403);
        HttpResponse<String> accepted = postJson(path, manager, "{\"reason\":\"bank corrected delivery\"}", "retry-key-" + eventId);
        assertThat(accepted.statusCode()).isEqualTo(202); assertThat(accepted.body()).contains("\"duplicate\":false");
        HttpResponse<String> replay = postJson(path, manager, "{\"reason\":\"bank corrected delivery\"}", "retry-key-" + eventId);
        assertThat(replay.statusCode()).isEqualTo(202); assertThat(replay.body()).isEqualTo(accepted.body());
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_event where action = 'INGESTION_RETRIED'
                    and entity_id = ? and reason = 'bank corrected delivery'
                """, Integer.class, eventId)).isOne();
        assertThat(postJson(path, manager, "{\"reason\":\"different\"}", "retry-key-other-" + eventId).body()).contains("EVENT_NOT_RETRYABLE");
        assertThat(postJson(path, manager, "{\"reason\":\"bank corrected delivery\"}", "retry-key-" + eventId + "-changed").body()).contains("EVENT_NOT_RETRYABLE");
    }

    @Test void operationalCollectionsPageAndValidateCursors() throws Exception {
        drainReceiptWorker();
        String demandReference = certifyDemand("10000000");
        String bankReceiptId = "bank-page-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-page-" + UUID.randomUUID(), bankReceiptId, "1", demandReference));
        acceptWebhook(receiptPayload("evt-page-missing-" + UUID.randomUUID(),
                "bank-page-missing-" + UUID.randomUUID(), "1", null));
        drainReceiptWorker();
        UUID receiptId = jdbcTemplate.queryForObject("select id from receipt where bank_receipt_id = ?", UUID.class, bankReceiptId);
        HttpResponse<String> events = get("/api/v1/ingestion-events?status=PROCESSED&limit=1", "manager");
        assertThat(events.statusCode()).isEqualTo(200);
        assertThat(events.body()).contains("\"items\"").contains("\"nextCursor\"");
        assertThat(get("/api/v1/ingestion-events?cursor=invalid", "manager").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/ingestion-events", "accounts").statusCode()).isEqualTo(403);
        HttpResponse<String> entries = get("/api/v1/financial-entries?receiptId=" + receiptId + "&limit=1", "accounts");
        assertThat(entries.statusCode()).isEqualTo(200);
        assertThat(entries.body()).contains("\"actorId\"", "\"actorDisplayName\"");
        String entryCursor = entries.body().replaceFirst(".*\\\"nextCursor\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(entryCursor).isNotEqualTo(entries.body());
        assertThat(get("/api/v1/financial-entries?receiptId=" + receiptId + "&limit=1&cursor=" + entryCursor,
                "accounts").body()).contains("\"nextCursor\":null");
        assertThat(get("/api/v1/financial-entries?receiptId=" + receiptId + "&demandId=" + UUID.randomUUID(),
                "accounts").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/exceptions?status=OPEN&limit=1", "certifier").body())
                .contains("MISSING_REFERENCE").contains("\"supportedActions\":[]");
        assertThat(get("/api/v1/exceptions?cursor=bad", "certifier").statusCode()).isEqualTo(400);
    }

    @Test void financialInspectionDoesNotCrossTheTrustedProjectBoundary() throws Exception {
        UUID clientId = UUID.randomUUID(), projectId = UUID.randomUUID(), receiptId = UUID.randomUUID();
        String bankId = "bank-other-project-" + UUID.randomUUID();
        try (Connection owner = ownerConnection()) {
            try (var insert = owner.prepareStatement("insert into client (id, name) values (?, 'Other synthetic client')")) {
                insert.setObject(1, clientId); insert.executeUpdate();
            }
            try (var insert = owner.prepareStatement("insert into project (id, client_id, code, name) values (?, ?, ?, 'Other synthetic project')")) {
                insert.setObject(1, projectId); insert.setObject(2, clientId);
                insert.setString(3, "OTHER-" + projectId); insert.executeUpdate();
            }
            try (var insert = owner.prepareStatement("""
                    insert into receipt (id, source, bank_receipt_id, project_id, amount_paise, currency, posted_at, fact_hash)
                    values (?, 'fixture', ?, ?, 1, 'INR', now(), repeat('0', 64))
                    """)) {
                insert.setObject(1, receiptId); insert.setString(2, bankId); insert.setObject(3, projectId);
                insert.executeUpdate();
            }
            try (var insert = owner.prepareStatement("""
                    insert into financial_entry (kind, receipt_id, amount_paise, actor_id, reason)
                    values ('RECEIPT', ?, 1, '10000000-0000-0000-0000-000000000004', 'SCOPE_TEST')
                    """)) {
                insert.setObject(1, receiptId); insert.executeUpdate();
            }
            try (var insert = owner.prepareStatement("""
                    insert into exception_case (type, project_id, receipt_id, source, bank_receipt_id, dedupe_key, reason_code)
                    values ('UNALLOCATED_FUNDS', ?, ?, 'fixture', ?, ?, 'MISSING_REFERENCE')
                    """)) {
                insert.setObject(1, projectId); insert.setObject(2, receiptId); insert.setString(3, bankId);
                insert.setString(4, "other-project:" + receiptId); insert.executeUpdate();
            }
        }
        assertThat(get("/api/v1/receipts/" + receiptId, "accounts").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/financial-entries?receiptId=" + receiptId, "manager").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/exceptions?status=OPEN", "certifier").body()).doesNotContain(bankId);
        UUID exceptionId = jdbcTemplate.queryForObject("select id from exception_case where receipt_id=?",
                UUID.class, receiptId);
        assertThat(get("/api/v1/exceptions/" + exceptionId + "/notes", "accounts").statusCode()).isEqualTo(404);
        Client accounts = client("accounts");
        assertThat(postJson("/api/v1/exceptions/" + exceptionId + "/notes", accounts,
                "{\"reason\":\"Evidence\"}", "scoped-note-" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(postJson("/api/v1/receipts/" + receiptId + "/allocations", accounts,
                allocationBody(UUID.randomUUID(), "1"), "scoped-allocation-" + UUID.randomUUID()).statusCode())
                .isEqualTo(404);
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
            assertThat(get(port, "/api/v1/ingestion-events/" + ingestionEventId, "manager").body())
                    .containsAnyOf("\"status\":\"PENDING\"", "\"status\":\"PROCESSED\"");
        }
    }

    @Test void collectionsWorklistsAndManualAllocationKeepEvidenceAndResiduals() throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand("10000000");
        UUID demandId = jdbcTemplate.queryForObject("select id from demand where reference = ?", UUID.class, reference);
        String bankId = "bank-manual-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-manual-" + UUID.randomUUID(), bankId, "4000000", null));
        drainReceiptWorker();
        UUID receiptId = jdbcTemplate.queryForObject("select id from receipt where bank_receipt_id = ?", UUID.class, bankId);
        UUID exceptionId = jdbcTemplate.queryForObject("select id from exception_case where receipt_id = ? and type = 'UNALLOCATED_FUNDS'", UUID.class, receiptId);
        assertThat(get("/api/v1/demands?status=OPEN&status=PARTIALLY_PAID", "accounts").body()).contains("\"ingestion\"").contains(reference);
        assertThat(get("/api/v1/receipts?status=UNALLOCATED", "accounts").body()).contains(bankId);
        assertThat(get("/api/v1/exceptions?status=OPEN", "accounts").body()).contains("ALLOCATE", "ADD_NOTE");
        assertThat(get("/api/v1/exceptions?status=OPEN", "certifier").body()).doesNotContain("\"ALLOCATE\"", "\"ADD_NOTE\"");

        Client accounts = client("accounts");
        String noteKey = "note-" + UUID.randomUUID();
        HttpResponse<String> note = postJson("/api/v1/exceptions/" + exceptionId + "/notes", accounts,
                "{\"reason\":\"Remittance advice received\"}", noteKey);
        assertThat(note.statusCode()).isEqualTo(201);
        assertThat(note.body()).contains("\"actorId\"", "\"createdAt\"");
        String noteCreatedAt = note.body().replaceFirst(".*\"createdAt\":\"([^\"]+)\".*", "$1");
        assertThat(get("/api/v1/exceptions/" + exceptionId + "/notes", "certifier").body())
                .contains("Remittance advice received", "\"createdAt\":\"" + noteCreatedAt + "\"");
        assertThat(postJson("/api/v1/exceptions/" + exceptionId + "/notes", client("manager"),
                "{\"reason\":\"Manager reviewed the evidence\"}", "manager-note-" + UUID.randomUUID()).statusCode())
                .isEqualTo(201);
        assertThat(get("/api/v1/receipts/" + receiptId, "accounts").body()).contains("\"unallocatedPaise\":\"4000000\"");
        assertThat(jdbcTemplate.queryForObject("select status from exception_case where id=?", String.class, exceptionId)).isEqualTo("OPEN");
        acceptWebhook(receiptPayload("evt-manual-conflict-" + UUID.randomUUID(), bankId, "4000001", null));
        drainReceiptWorker();
        UUID conflictId = jdbcTemplate.queryForObject("select id from exception_case where receipt_id=? and type='BANK_RECORD_CONFLICT'", UUID.class, receiptId);

        String body = "{\"demandId\":\"" + demandId + "\",\"amountPaise\":\"1500000\",\"reason\":\"Advice identifies demand\"}";
        String key = "allocation-" + UUID.randomUUID();
        HttpResponse<String> partial = postJson("/api/v1/receipts/" + receiptId + "/allocations", accounts, body, key);
        assertThat(partial.statusCode()).isEqualTo(201);
        assertThat(postJson("/api/v1/receipts/" + receiptId + "/allocations", accounts, body, key).body()).isEqualTo(partial.body());
        assertThat(jdbcTemplate.queryForObject("select status from exception_case where id = ?", String.class, exceptionId)).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id = ? and kind = 'ALLOCATION'", Integer.class, receiptId)).isOne();
        assertThat(get("/api/v1/receipts?status=PARTIALLY_ALLOCATED", "accounts").body()).contains(receiptId.toString());
        assertThat(get("/api/v1/demands?status=PARTIALLY_PAID", "accounts").body()).contains(demandId.toString());

        HttpResponse<String> finalAllocation = postJson("/api/v1/receipts/" + receiptId + "/allocations", accounts,
                "{\"demandId\":\"" + demandId + "\",\"amountPaise\":\"2500000\",\"reason\":\"Remaining evidenced funds\"}", "allocation-final-" + UUID.randomUUID());
        assertThat(finalAllocation.statusCode()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("select status from exception_case where id = ?", String.class, exceptionId)).isEqualTo("RESOLVED");
        assertThat(jdbcTemplate.queryForObject("select status from exception_case where id = ?", String.class, conflictId)).isEqualTo("OPEN");
        assertThat(get("/api/v1/demands/" + demandId, "accounts").body()).contains("\"outstandingPaise\":\"6000000\"");
        assertThat(get("/api/v1/receipts?status=ALLOCATED", "accounts").body()).contains(receiptId.toString());
        assertThat(postJson("/api/v1/exceptions/" + exceptionId + "/notes", accounts,
                "{\"reason\":\"Final evidence filed\"}", "resolved-note-" + UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("select status from exception_case where id = ?", String.class, exceptionId)).isEqualTo("RESOLVED");
        assertThat(postJson("/api/v1/exceptions/" + conflictId + "/notes", client("certifier"),
                "{\"reason\":\"Denied\"}", "denied-note-" + UUID.randomUUID()).statusCode()).isEqualTo(403);
        try (ConfigurableApplicationContext restarted = freshApplication()) {
            int port = ((WebServerApplicationContext) restarted).getWebServer().getPort();
            Client restartedAccounts = client(port, "accounts");
            assertThat(get(port, "/api/v1/exceptions/" + exceptionId + "/notes", "accounts").body()).contains("Remittance advice received");
            assertThat(get(port, "/api/v1/demands/" + demandId, "accounts").body()).contains("\"outstandingPaise\":\"6000000\"");
            assertThat(post(port, "/api/v1/receipts/" + receiptId + "/allocations", restartedAccounts, body, key).body()).isEqualTo(partial.body());
            assertThat(post(port, "/api/v1/exceptions/" + exceptionId + "/notes", restartedAccounts,
                    "{\"reason\":\"Remittance advice received\"}", noteKey).body()).isEqualTo(note.body());
        }
    }

    @Test void manualAllocationRejectsInvalidAndOverBudgetRequestsWithoutCachingFailures() throws Exception {
        ManualFixture fixture = manualFixture("100", "80");
        Client accounts = client("accounts");
        String path = "/api/v1/receipts/" + fixture.receiptId() + "/allocations";
        String invalidKey = "invalid-" + UUID.randomUUID();
        for (String body : List.of(
                "{\"demandId\":\"" + fixture.demandId() + "\",\"amountPaise\":80,\"reason\":\"evidence\"}",
                "{\"demandId\":\"" + fixture.demandId() + "\",\"amountPaise\":\"1.5\",\"reason\":\"evidence\"}",
                "{\"demandId\":\"" + fixture.demandId() + "\",\"amountPaise\":\"1\",\"reason\":\"  \"}",
                "{\"demandId\":\"" + fixture.demandId() + "\",\"amountPaise\":\"1\",\"reason\":\"evidence\",\"extra\":1}")) {
            assertThat(postJson(path, accounts, body, invalidKey).statusCode()).isEqualTo(400);
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from idempotency_request where idempotency_key=?", Integer.class, invalidKey)).isZero();
        assertThat(postJson(path, accounts, allocationBody(fixture.demandId(), "81"), "too-much-receipt-" + UUID.randomUUID()).body())
                .contains("ALLOCATION_EXCEEDS_UNALLOCATED");
        UUID smallDemand = jdbcTemplate.queryForObject("select id from demand where reference=?", UUID.class, certifyDemand("10"));
        assertThat(postJson(path, accounts, allocationBody(smallDemand, "11"), "too-much-demand-" + UUID.randomUUID()).body())
                .contains("ALLOCATION_EXCEEDS_OUTSTANDING");
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id=? and kind='ALLOCATION'", Integer.class, fixture.receiptId())).isZero();
        assertThat(postJson(path, client("certifier"), allocationBody(fixture.demandId(), "1"), "certifier-denied-" + UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(post(path, "accounts", allocationBody(fixture.demandId(), "1"), "csrf-denied-" + UUID.randomUUID(), null).statusCode()).isEqualTo(403);
    }

    @Test void mutationBoundaryRejectsMissingKeysOverflowAndUnsafeReasonsWithoutEffects() throws Exception {
        ManualFixture fixture = manualFixture("100", "80");
        Client accounts = client("accounts");
        String allocationPath = "/api/v1/receipts/" + fixture.receiptId() + "/allocations";
        String notePath = "/api/v1/exceptions/" + fixture.exceptionId() + "/notes";
        String validAllocation = allocationBody(fixture.demandId(), "1");
        for (String body : List.of(
                allocationBody(fixture.demandId(), "0"),
                allocationBody(fixture.demandId(), "-1"),
                allocationBody(fixture.demandId(), "9223372036854775808"),
                "{\"demandId\":\"bad\",\"amountPaise\":\"1\",\"reason\":\"evidence\"}",
                "{\"demandId\":\"" + fixture.demandId() + "\",\"amountPaise\":\"1\",\"reason\":\" evidence\"}",
                "{\"demandId\":\"" + fixture.demandId() + "\",\"amountPaise\":\"1\",\"reason\":\"" + "x".repeat(501) + "\"}")) {
            assertThat(postJson(allocationPath, accounts, body, "invalid-mutation-" + UUID.randomUUID()).statusCode())
                    .as(body).isEqualTo(400);
        }
        HttpResponse<String> noKey = accounts.http().send(HttpRequest.newBuilder(uri(allocationPath))
                .header("Authorization", basic("accounts")).header("X-XSRF-TOKEN", accounts.csrf())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(validAllocation))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noKey.statusCode()).isEqualTo(400);
        assertThat(noKey.body()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(postJson(allocationPath, accounts, validAllocation, "k".repeat(201)).body())
                .contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(postJson(allocationPath, accounts, allocationBody(UUID.randomUUID(), "1"),
                "missing-demand-" + UUID.randomUUID()).statusCode()).isEqualTo(404);

        for (String body : List.of("{}", "{\"reason\":\"  \"}",
                "{\"reason\":\"note\",\"extra\":true}", "{\"reason\":\"" + "x".repeat(501) + "\"}")) {
            assertThat(postJson(notePath, accounts, body, "invalid-note-" + UUID.randomUUID()).statusCode())
                    .as(body).isEqualTo(400);
        }
        HttpResponse<String> noNoteKey = accounts.http().send(HttpRequest.newBuilder(uri(notePath))
                .header("Authorization", basic("accounts")).header("X-XSRF-TOKEN", accounts.csrf())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"evidence\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noNoteKey.statusCode()).isEqualTo(400);
        assertThat(noNoteKey.body()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(post(notePath, "accounts", "{\"reason\":\"evidence\"}",
                "csrf-note-" + UUID.randomUUID(), null).statusCode()).isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id=? and kind='ALLOCATION'",
                Integer.class, fixture.receiptId())).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_event where action='EXCEPTION_NOTE' and entity_id=?",
                Integer.class, fixture.exceptionId())).isZero();
    }

    @Test void concurrentManualRequestsCannotSpendSameReceiptTwice() throws Exception {
        ManualFixture fixture = manualFixture("200", "100");
        String path = "/api/v1/receipts/" + fixture.receiptId() + "/allocations";
        String body = allocationBody(fixture.demandId(), "70");
        Client accounts = client("accounts"), manager = client("manager");
        try (Connection owner = ownerConnection(); Connection observer = ownerConnection();
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            owner.setAutoCommit(false);
            try (var lock = owner.prepareStatement("select id from receipt where id=? for update")) {
                lock.setObject(1, fixture.receiptId());
                lock.executeQuery().close();
            }
            var first = executor.submit(() -> postJson(path, accounts, body, "race-a-" + UUID.randomUUID()));
            var second = executor.submit(() -> postJson(path, manager, body, "race-b-" + UUID.randomUUID()));
            try {
                assertThat(waitingRowLocks(observer, "receipt", 2)).isTrue();
            } finally {
                owner.commit();
            }
            List<Integer> statuses = List.of(first.get().statusCode(), second.get().statusCode());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbcTemplate.queryForObject("select sum(amount_paise) from financial_entry where receipt_id=? and kind='ALLOCATION'", Long.class, fixture.receiptId())).isEqualTo(70L);
        assertThat(jdbcTemplate.queryForObject("select sum(amount_paise) from financial_entry where demand_id=? and kind='ALLOCATION'", Long.class, fixture.demandId())).isEqualTo(70L);
    }

    @Test void concurrentSameKeyReplaysOneAllocationAndDifferentReceiptsCannotOverpayDemand() throws Exception {
        ManualFixture first = manualFixture("100", "100");
        String path = "/api/v1/receipts/" + first.receiptId() + "/allocations";
        String key = "same-key-race-" + UUID.randomUUID();
        String body = allocationBody(first.demandId(), "30");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> postJson(path, client("accounts"), body, key));
            var b = executor.submit(() -> postJson(path, client("accounts"), body, key));
            HttpResponse<String> aResult = a.get(), bResult = b.get();
            assertThat(aResult.statusCode()).isEqualTo(201);
            assertThat(bResult.statusCode()).isEqualTo(201);
            assertThat(aResult.body()).isEqualTo(bResult.body());
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id=? and kind='ALLOCATION'", Integer.class, first.receiptId())).isOne();

        String bankId = "bank-demand-race-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-demand-race-" + UUID.randomUUID(), bankId, "80", null));
        drainReceiptWorker();
        UUID secondReceipt = jdbcTemplate.queryForObject("select id from receipt where bank_receipt_id=?", UUID.class, bankId);
        Client accounts = client("accounts"), manager = client("manager");
        try (Connection owner = ownerConnection(); Connection observer = ownerConnection();
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            owner.setAutoCommit(false);
            try (var lock = owner.prepareStatement("select id from demand where id=? for update")) {
                lock.setObject(1, first.demandId());
                lock.executeQuery().close();
            }
            var a = executor.submit(() -> postJson(path, accounts, allocationBody(first.demandId(), "60"), "demand-a-" + UUID.randomUUID()));
            var b = executor.submit(() -> postJson("/api/v1/receipts/" + secondReceipt + "/allocations", manager, allocationBody(first.demandId(), "60"), "demand-b-" + UUID.randomUUID()));
            try {
                assertThat(waitingRowLocks(observer, "demand", 2)).isTrue();
            } finally {
                owner.commit();
            }
            assertThat(List.of(a.get().statusCode(), b.get().statusCode())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbcTemplate.queryForObject("select sum(amount_paise) from financial_entry where demand_id=? and kind='ALLOCATION'", Long.class, first.demandId())).isEqualTo(90L);
        assertThat(jdbcTemplate.queryForObject("select coalesce(sum(amount_paise),0) from financial_entry where receipt_id=? and kind='ALLOCATION'", Long.class, secondReceipt)).isLessThanOrEqualTo(80L);
    }

    @Test void collectionCursorsAreFilterBoundAndNotesReplayWithoutChangingCase() throws Exception {
        ManualFixture fixture = manualFixture("100", "80");
        String demandPage = get("/api/v1/demands?status=OPEN&status=PARTIALLY_PAID&limit=1", "accounts").body();
        String cursor = demandPage.replaceFirst(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        assertThat(cursor).isNotEqualTo(demandPage);
        assertThat(get("/api/v1/demands?status=PARTIALLY_PAID&status=OPEN&limit=1&cursor=" + cursor, "accounts").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/demands?status=SETTLED&limit=1&cursor=" + cursor, "accounts").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/receipts?cursor=invalid", "accounts").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/receipts?projectId=30000000-0000-0000-0000-000000000099", "accounts").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/financial-entries?receiptId=" + fixture.receiptId() + "&demandId=" + fixture.demandId(), "accounts").statusCode()).isEqualTo(400);

        String path = "/api/v1/exceptions/" + fixture.exceptionId() + "/notes";
        assertThat(get(path, "certifier").body()).contains("\"items\":[]");
        assertThat(get("/api/v1/exceptions/" + UUID.randomUUID() + "/notes", "certifier").statusCode()).isEqualTo(404);
        String key = "note-replay-" + UUID.randomUUID();
        String body = "{\"reason\":\"Bank advice requested\"}";
        Client accounts = client("accounts");
        HttpResponse<String> original = postJson(path, accounts, body, key);
        assertThat(original.statusCode()).isEqualTo(201);
        assertThat(postJson(path, accounts, body, key).body()).isEqualTo(original.body());
        assertThat(postJson(path, accounts, "{\"reason\":\"Different advice\"}", key).body()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_event where action='EXCEPTION_NOTE' and entity_id=?", Integer.class, fixture.exceptionId())).isOne();
        assertThat(jdbcTemplate.queryForObject("select status from exception_case where id=?", String.class, fixture.exceptionId())).isEqualTo("OPEN");
    }

    @Test void collectionBoundariesDistinguishInvalidFiltersAndEmptyHistoryFromMissingResources() throws Exception {
        ManualFixture fixture = manualFixture("100", "80");
        String project = "30000000-0000-0000-0000-000000000001";
        assertThat(get("/api/v1/demands?projectId=" + project + "&status=OPEN", "certifier").body())
                .contains(fixture.demandId().toString());
        assertThat(get("/api/v1/receipts?projectId=" + project + "&status=UNALLOCATED", "manager").body())
                .contains(fixture.receiptId().toString());
        for (String path : List.of("/api/v1/demands?status=UNKNOWN", "/api/v1/receipts?status=OPEN",
                "/api/v1/demands?limit=0", "/api/v1/receipts?limit=101",
                "/api/v1/exceptions?limit=-1", "/api/v1/financial-entries?receiptId=" + fixture.receiptId() + "&limit=101",
                "/api/v1/financial-entries", "/api/v1/financial-entries?receiptId=not-a-uuid")) {
            HttpResponse<String> response = get(path, "accounts");
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.body()).as(path).contains("VALIDATION_ERROR");
        }
        assertThat(get("/api/v1/demands?projectId=" + UUID.randomUUID(), "accounts").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/receipts?projectId=" + UUID.randomUUID(), "accounts").statusCode()).isEqualTo(404);

        UUID emptyReceipt = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into receipt (id, source, bank_receipt_id, project_id, amount_paise,
                    currency, posted_at, fact_hash)
                values (?, 'empty-history-test', ?, ?::uuid, 1, 'INR', now(), repeat('0',64))
                """, emptyReceipt, "bank-empty-history-" + emptyReceipt, project);
        HttpResponse<String> existing = get("/api/v1/financial-entries?receiptId=" + emptyReceipt, "accounts");
        assertThat(existing.statusCode()).isEqualTo(200);
        assertThat(existing.body()).contains("\"items\":[]", "\"nextCursor\":null");
        assertThat(get("/api/v1/financial-entries?receiptId=" + UUID.randomUUID(), "accounts").statusCode())
                .isEqualTo(404);
        assertThat(get("/api/v1/demands", "not-a-user").statusCode()).isEqualTo(401);
    }

    @Test void worklistDependencyFailureDoesNotLookLikeAnEmptySuccess() throws Exception {
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("revoke select on demand from milestone_app");
            try {
                HttpResponse<String> failed = get("/api/v1/demands?limit=1", "accounts");
                assertThat(failed.statusCode()).isEqualTo(503);
                assertThat(failed.body()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("\"items\":[]");
            } finally {
                statement.execute("grant select on demand to milestone_app");
            }
        }
        assertThat(get("/api/v1/demands?limit=1", "accounts").statusCode()).isEqualTo(200);
    }

    @Test void bankConflictWithoutReceiptHasUnknownResidualAndNoAllocationAction() throws Exception {
        UUID exceptionId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var insert = owner.prepareStatement("""
                insert into exception_case (id, type, project_id, source, bank_receipt_id,
                    dedupe_key, reason_code, first_seen_at, last_seen_at)
                values (?, 'BANK_RECORD_CONFLICT', ?::uuid, 'fixture', ?, ?,
                    'CHANGED_BANK_RECORD', ?::timestamptz, ?::timestamptz)
                """)) {
            insert.setObject(1, exceptionId);
            insert.setString(2, "30000000-0000-0000-0000-000000000001");
            insert.setString(3, "bank-no-receipt-" + exceptionId);
            insert.setString(4, "no-receipt-" + exceptionId);
            insert.setString(5, "2999-01-01T00:00:00Z");
            insert.setString(6, "2999-01-01T00:00:00Z");
            insert.executeUpdate();
        }
        String context = "exceptions:30000000-0000-0000-0000-000000000001:OPEN";
        String cursor = dev.sakshi.milestoneledger.shared.web.KeysetPage.encode(
                Instant.parse("2998-01-01T00:00:00Z"), new UUID(0, 0), context);
        String response = get("/api/v1/exceptions?status=OPEN&cursor=" + cursor, "manager").body();
        assertThat(response).contains(exceptionId.toString(), "\"residualAmountPaise\":null", "ADD_NOTE");
        assertThat(response).doesNotContain("\"ALLOCATE\"");
    }

    @Test void receiptWorklistPaginatesTiedTimestampsAndOutstandingExcludesSettledDemand() throws Exception {
        Instant tied = Instant.parse("2998-01-01T00:00:00Z");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        for (UUID receiptId : List.of(first, second)) {
            jdbcTemplate.update("""
                    insert into receipt (id, source, bank_receipt_id, project_id, amount_paise,
                        currency, posted_at, recorded_at, fact_hash)
                    values (?, 'pagination-fixture', ?, ?::uuid, 1, 'INR', ?, ?, repeat('0',64))
                    """, receiptId, "bank-page-" + receiptId,
                    "30000000-0000-0000-0000-000000000001", Timestamp.from(tied), Timestamp.from(tied));
            jdbcTemplate.update("""
                    insert into financial_entry (kind, receipt_id, amount_paise, actor_id, reason)
                    values ('RECEIPT', ?, 1, '10000000-0000-0000-0000-000000000004', 'PAGINATION_FIXTURE')
                    """, receiptId);
        }
        String context = "receipts:30000000-0000-0000-0000-000000000001:UNALLOCATED";
        String before = dev.sakshi.milestoneledger.shared.web.KeysetPage.encode(
                Instant.parse("2997-12-31T23:59:59Z"), UUID.randomUUID(), context);
        String firstPage = get("/api/v1/receipts?status=UNALLOCATED&limit=1&cursor=" + before, "accounts").body();
        String next = firstPage.replaceFirst(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        assertThat(next).isNotEqualTo(firstPage);
        String secondPage = get("/api/v1/receipts?status=UNALLOCATED&limit=1&cursor=" + next, "accounts").body();
        assertThat(firstPage + secondPage).contains(first.toString(), second.toString());
        assertThat(firstPage).doesNotContain(secondPage.replaceFirst(".*\"id\":\"([^\"]+)\".*", "$1"));

        ManualFixture settled = manualFixture("10", "10");
        assertThat(postJson("/api/v1/receipts/" + settled.receiptId() + "/allocations", client("accounts"),
                allocationBody(settled.demandId(), "10"), "settled-" + UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertThat(get("/api/v1/demands?status=OPEN&status=PARTIALLY_PAID", "accounts").body())
                .doesNotContain(settled.demandId().toString());
        assertThat(get("/api/v1/demands?status=SETTLED", "accounts").body())
                .contains(settled.demandId().toString());
    }

    @Test void worklistRowsAndIngestionMetadataUseOneSnapshotDuringConcurrentWrites() throws Exception {
        ManualFixture fixture = manualFixture("100", "80");
        String path = "/api/v1/demands?status=OPEN&limit=100";
        String baseline = get(path, "accounts").body();
        long pendingBefore = Long.parseLong(baseline.replaceFirst(".*\"pendingCount\":([0-9]+).*", "$1"));
        try (Connection blocker = ownerConnection(); Connection observer = ownerConnection();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("lock table inbox_event in access exclusive mode");
            var response = executor.submit(() -> get(path, "accounts"));
            try {
                boolean metadataWaiting = false;
                for (int attempt = 0; attempt < 100; attempt++) {
                    try (var count = observer.createStatement().executeQuery("""
                            select count(*) from pg_stat_activity where wait_event_type='Lock'
                            and query ilike '%from inbox_event where project_id%'
                            """)) {
                        count.next();
                        if (count.getInt(1) >= 1) { metadataWaiting = true; break; }
                    }
                    Thread.sleep(20);
                }
                assertThat(metadataWaiting).isTrue();
                try (var insert = blocker.prepareStatement("""
                        insert into financial_entry (kind, receipt_id, demand_id, amount_paise, actor_id, reason)
                        values ('ALLOCATION', ?, ?, 50, '10000000-0000-0000-0000-000000000004', 'SNAPSHOT_TEST')
                        """)) {
                    insert.setObject(1, fixture.receiptId());
                    insert.setObject(2, fixture.demandId());
                    insert.executeUpdate();
                }
                UUID eventId = UUID.randomUUID();
                try (var insert = blocker.prepareStatement("""
                        insert into inbox_event (source, event_id, project_id, account_reference,
                            bank_receipt_id, amount_paise, currency, demand_reference, posted_at, canonical_hash)
                        values ('snapshot-fixture', ?, ?::uuid, 'trusted-account', ?, 1, 'INR', null, now(), repeat('0',64))
                        """)) {
                    insert.setString(1, "evt-snapshot-" + eventId);
                    insert.setString(2, "30000000-0000-0000-0000-000000000001");
                    insert.setString(3, "bank-snapshot-" + eventId);
                    insert.executeUpdate();
                }
            } finally {
                blocker.commit();
            }
            String snapshot = response.get().body();
            assertThat(snapshot).contains("\"id\":\"" + fixture.demandId() + "\"");
            assertThat(Long.parseLong(snapshot.replaceFirst(".*\"pendingCount\":([0-9]+).*", "$1")))
                    .isEqualTo(pendingBefore);
        }
        String current = get("/api/v1/demands?status=PARTIALLY_PAID&limit=100", "accounts").body();
        assertThat(current).contains("\"id\":\"" + fixture.demandId() + "\"");
        assertThat(Long.parseLong(current.replaceFirst(".*\"pendingCount\":([0-9]+).*", "$1")))
                .isEqualTo(pendingBefore + 1);
        drainReceiptWorker();
    }

    @Test void failedIngestionIsVisibleWithoutCountingUnprocessedMoney() throws Exception {
        ManualFixture fixture = manualFixture("100", "80");
        String before = get("/api/v1/receipts?status=UNALLOCATED", "accounts").body();
        long failedBefore = Long.parseLong(before.replaceFirst(".*\"failedCount\":([0-9]+).*", "$1"));
        String eventId = "failed-worklist-" + UUID.randomUUID();
        try (Connection owner = ownerConnection(); var insert = owner.prepareStatement("""
                insert into inbox_event (source, event_id, project_id, account_reference,
                    bank_receipt_id, amount_paise, currency, demand_reference, posted_at,
                    canonical_hash, status, last_error_code)
                values ('worklist-test', ?, ?::uuid, 'trusted-account', ?, 999999,
                    'INR', null, now(), repeat('0',64), 'FAILED', 'TEST_FAILURE')
                """)) {
            insert.setString(1, eventId);
            insert.setString(2, "30000000-0000-0000-0000-000000000001");
            insert.setString(3, "bank-" + eventId);
            insert.executeUpdate();
        }
        String after = get("/api/v1/receipts?status=UNALLOCATED", "accounts").body();
        assertThat(after).contains(fixture.receiptId().toString(), "\"failedCount\":" + (failedBefore + 1));
        assertThat(after).doesNotContain(eventId);
        assertThat(get("/api/v1/receipts/" + fixture.receiptId(), "accounts").body())
                .contains("\"unallocatedPaise\":\"80\"");
    }

    @Test void manualAndAutomaticAllocationRaceUnderTheDemandRowLock() throws Exception {
        ManualFixture manual = manualFixture("100", "80");
        String reference = jdbcTemplate.queryForObject("select reference from demand where id=?", String.class, manual.demandId());
        String autoBankId = "bank-auto-race-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-auto-race-" + UUID.randomUUID(), autoBankId, "80", reference));
        Client accounts = client("accounts");
        try (Connection owner = ownerConnection(); Connection observer = ownerConnection(); ExecutorService executor = Executors.newFixedThreadPool(2)) {
            owner.setAutoCommit(false);
            try (var lock = owner.prepareStatement("select id from demand where id=? for update")) {
                lock.setObject(1, manual.demandId());
                lock.executeQuery().close();
            }
            var posted = executor.submit(() -> postJson("/api/v1/receipts/" + manual.receiptId() + "/allocations",
                    accounts, allocationBody(manual.demandId(), "80"), "auto-race-" + UUID.randomUUID()));
            var processed = executor.submit(receiptProcessor::processOneDueEvent);
            try {
                boolean bothWaiting = false;
                for (int attempt = 0; attempt < 100; attempt++) {
                    try (var count = observer.createStatement().executeQuery("""
                            select count(*) from pg_stat_activity
                            where wait_event_type='Lock' and query ilike '%from demand%' and query ilike '%for update%'
                            """)) {
                        count.next();
                        if (count.getInt(1) >= 2) { bothWaiting = true; break; }
                    }
                    Thread.sleep(20);
                }
                assertThat(bothWaiting).isTrue();
            } finally {
                owner.commit();
            }
            assertThat(processed.get()).isTrue();
            assertThat(posted.get().statusCode()).isIn(201, 409);
        }
        UUID autoReceipt = jdbcTemplate.queryForObject("select id from receipt where bank_receipt_id=?", UUID.class, autoBankId);
        Long demandAllocated = jdbcTemplate.queryForObject("select sum(amount_paise) from financial_entry where demand_id=? and kind='ALLOCATION'", Long.class, manual.demandId());
        Long manualAllocated = jdbcTemplate.queryForObject("select coalesce(sum(amount_paise),0) from financial_entry where receipt_id=? and kind='ALLOCATION'", Long.class, manual.receiptId());
        Long automaticAllocated = jdbcTemplate.queryForObject("select coalesce(sum(amount_paise),0) from financial_entry where receipt_id=? and kind='ALLOCATION'", Long.class, autoReceipt);
        assertThat(demandAllocated).isEqualTo(manualAllocated + automaticAllocated).isBetween(80L, 100L);
        assertThat(manualAllocated).isBetween(0L, 80L);
        assertThat(automaticAllocated).isBetween(0L, 80L);
    }

    @Test void manualAllocationFailureAfterEachWriteRollsBackMoneyAuditAndIdempotency() throws Exception {
        for (String[] boundary : List.of(new String[] {"financial_entry", "insert", "new.kind='ALLOCATION'"},
                new String[] {"exception_case", "update", "new.status='RESOLVED'"},
                new String[] {"audit_event", "insert", "new.action='ALLOCATION_RECORDED'"})) {
            ManualFixture fixture = manualFixture("100", "80");
            String key = "manual-rollback-" + UUID.randomUUID();
            String trigger = "test_manual_" + UUID.randomUUID().toString().replace('-', '_');
            try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
                statement.executeUpdate("create function " + trigger + "() returns trigger language plpgsql as $$ begin raise exception 'test injected failure'; end; $$");
                statement.executeUpdate("create trigger " + trigger + " after " + boundary[1] + " on " + boundary[0]
                        + " for each row when (" + boundary[2] + ") execute function " + trigger + "()");
                assertThat(postJson("/api/v1/receipts/" + fixture.receiptId() + "/allocations", client("accounts"),
                        allocationBody(fixture.demandId(), "80"), key).statusCode()).isEqualTo(503);
            } finally {
                try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
                    statement.executeUpdate("drop trigger if exists " + trigger + " on " + boundary[0]);
                    statement.executeUpdate("drop function if exists " + trigger + "()");
                }
            }
            assertThat(jdbcTemplate.queryForObject("select count(*) from financial_entry where receipt_id=? and kind='ALLOCATION'", Integer.class, fixture.receiptId())).isZero();
            assertThat(jdbcTemplate.queryForObject("select status from exception_case where id=?", String.class, fixture.exceptionId())).isEqualTo("OPEN");
            assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from audit_event
                    where entity_id=? and action in ('ALLOCATION_RECORDED','RESIDUAL_FUNDS_RESOLVED')
                    """, Integer.class, fixture.receiptId())).isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from idempotency_request where idempotency_key=?", Integer.class, key)).isZero();
        }
    }

    private ManualFixture manualFixture(String demandAmount, String receiptAmount) throws Exception {
        drainReceiptWorker();
        String reference = certifyDemand(demandAmount);
        UUID demandId = jdbcTemplate.queryForObject("select id from demand where reference=?", UUID.class, reference);
        String bankId = "bank-manual-fixture-" + UUID.randomUUID();
        acceptWebhook(receiptPayload("evt-manual-fixture-" + UUID.randomUUID(), bankId, receiptAmount, null));
        drainReceiptWorker();
        UUID receiptId = jdbcTemplate.queryForObject("select id from receipt where bank_receipt_id=?", UUID.class, bankId);
        UUID exceptionId = jdbcTemplate.queryForObject("select id from exception_case where receipt_id=? and type='UNALLOCATED_FUNDS'", UUID.class, receiptId);
        return new ManualFixture(receiptId, demandId, exceptionId);
    }
    private String allocationBody(UUID demandId, String amount) {
        return "{\"demandId\":\"" + demandId + "\",\"amountPaise\":\"" + amount + "\",\"reason\":\"Documented remittance\"}";
    }
    private record ManualFixture(UUID receiptId, UUID demandId, UUID exceptionId) { }

    private boolean waitingRowLocks(Connection observer, String table, int expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (var query = observer.prepareStatement("""
                    select count(*) from pg_stat_activity
                    where wait_event_type='Lock' and query ilike ? and query ilike '%for update%'
                    """)) {
                query.setString(1, "%from " + table + "%");
                try (var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= expected) return true;
                }
            }
            Thread.sleep(20);
        }
        return false;
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

    private void drainReceiptWorker() {
        for (int i = 0; i < 100 && receiptProcessor.processOneDueEvent(); i++) {
            // The test owns a shared durable inbox; drain due work rather than assuming test execution order.
        }
    }

    private String certifyDemand(String amount) throws Exception {
        UUID milestoneId = scheduledMilestone();
        String body = "{\"approvedAmountPaise\":\"" + amount + "\",\"currency\":\"INR\",\"certificationReference\":\"CERT-PAYMENT-" + UUID.randomUUID() + "\"}";
        HttpResponse<String> response = post("/api/v1/milestones/" + milestoneId + "/certification", client("certifier"), body, "payment-cert-" + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(201);
        return response.body().replaceFirst(".*\\\"reference\\\":\\\"(DEM-[0-9a-f-]{36})\\\".*", "$1");
    }

    private String receiptPayload(String eventId, String bankReceiptId, String amount, String reference) {
        String referenceField = reference == null ? "" : ",\"demandReference\":\"" + reference + "\"";
        return "{\"eventId\":\"" + eventId + "\",\"receipt\":{\"bankReceiptId\":\"" + bankReceiptId + "\",\"amountPaise\":\"" + amount + "\",\"currency\":\"INR\"" + referenceField + ",\"postedAt\":\"2026-09-16T10:00:00Z\"}}";
    }

    private void acceptWebhook(String payload) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        assertThat(webhook(payload, timestamp, signature(timestamp, payload)).statusCode()).isEqualTo(202);
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

    private HttpResponse<String> postJson(String path, Client client, String body, String key) throws Exception {
        return client.http().send(HttpRequest.newBuilder(uri(path)).header("Authorization", basic(client.username()))
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
