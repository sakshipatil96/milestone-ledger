package dev.sakshi.milestoneledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import dev.sakshi.milestoneledger.payments.ReceiptProcessor;
import dev.sakshi.milestoneledger.payments.PaymentMutationService;
import dev.sakshi.milestoneledger.ingestion.BankWebhookService;
import dev.sakshi.milestoneledger.ingestion.ReceiptFacts;
import dev.sakshi.milestoneledger.ingestion.InboxEventStore;
import dev.sakshi.milestoneledger.reconciliation.ReconciliationService;
import dev.sakshi.milestoneledger.reconciliation.ReconciliationWorker;
import dev.sakshi.milestoneledger.reconciliation.BankSnapshotClient;
import dev.sakshi.milestoneledger.shared.web.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.setup.project-id=30000000-0000-0000-0000-000000000001",
        "app.security.certifier-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
        "app.security.accounts-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
        "app.security.manager-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
        "app.bank.webhook.signing-secret=test-webhook-secret",
        "app.bank.webhook.project-id=30000000-0000-0000-0000-000000000001",
        "app.receipt-worker.initial-delay-ms=60000",
        "app.reconciliation-worker.initial-delay-ms=60000" })
class ReconciliationIT {
    private static final UUID PROJECT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final AtomicReference<String> PAGE = new AtomicReference<>();
    private static final AtomicReference<Map<String, String>> PAGES = new AtomicReference<>(Map.of());
    private static final AtomicReference<Map<String, Integer>> CODES = new AtomicReference<>(Map.of());
    private static final HttpServer SERVER;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/receipts", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null && CODES.get().containsKey(query)) {
                    exchange.sendResponseHeaders(CODES.get().get(query), -1);
                    exchange.close();
                    return;
                }
                String body = query == null ? PAGE.get() : PAGES.get().getOrDefault(query, PAGE.get());
                if (body == null) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            SERVER.start();
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @AfterAll static void stopServer() { SERVER.stop(0); }

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("reconciliation_test").withUsername("test_owner").withPassword("test-owner-password")
            .withInitScript("db/test/create-runtime-role.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "milestone_app");
        r.add("spring.datasource.password", () -> "test-app-password");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("app.bank.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @Autowired ReconciliationService service;
    @Autowired ReconciliationWorker worker;
    @Autowired ReceiptProcessor receipts;
    @Autowired BankWebhookService webhooks;
    @Autowired PaymentMutationService mutations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired InboxEventStore inbox;
    @Autowired BankSnapshotClient snapshotClient;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test @Order(1) void omittedReceiptRecoversOnceAndRepeatRunCannotDuplicateMoney() throws Exception {
        String bankId = "missed-one";
        String posted = Instant.now().minusSeconds(30).toString();
        PAGE.set(page("stable-1", item(bankId, "5000", posted)));
        manager();
        UUID run = service.create(PROJECT, "first", "test-first").runId();
        assertThat(service.create(PROJECT, "first", "test-first").runId()).isEqualTo(run);
        assertThatThrownBy(() -> service.create(PROJECT, "second-while-active", "test-first"))
                .isInstanceOf(ApiException.class).hasMessageContaining("already active");
        complete(run);
        assertThat(service.get(run).status()).isEqualTo("COMPLETED");
        assertThat(service.get(run).recoveredCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from receipt where bank_receipt_id=?", Integer.class, bankId)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from financial_entry f join receipt r on r.id=f.receipt_id
                where r.bank_receipt_id=? and f.kind='RECEIPT'
                """, Integer.class, bankId)).isOne();

        PAGE.set(page("stable-2", item(bankId, "5000", posted)));
        UUID repeat = service.create(PROJECT, "repeat", "test-repeat").runId();
        complete(repeat);
        assertThat(service.get(repeat).recoveredCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from receipt where bank_receipt_id=?", Integer.class, bankId)).isOne();
    }

    @Test @Order(2) void completeComparisonReportsAndLaterResolvesChangedFacts() throws Exception {
        String posted = jdbc.queryForObject("select posted_at::text from receipt where bank_receipt_id='missed-one'", String.class);
        // A changed amount cannot change the original receipt or its financial entry.
        PAGE.set(page("changed-1", item("missed-one", "5001", Instant.now().minusSeconds(30).toString())));
        manager();
        UUID run = service.create(PROJECT, "changed", "test-changed").runId();
        complete(run);
        assertThat(service.get(run).conflictCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount_paise from receipt where bank_receipt_id='missed-one'", Long.class)).isEqualTo(5000L);
        assertThat(jdbc.queryForObject("""
                select count(*) from exception_case where type='BANK_RECORD_CONFLICT' and bank_receipt_id='missed-one' and status='OPEN'
                """, Integer.class)).isOne();

        UUID exception = jdbc.queryForObject("""
                select id from exception_case where type='BANK_RECORD_CONFLICT' and bank_receipt_id='missed-one'
                """, UUID.class);
        accounts();
        mutations.addNote(exception, "conflict-note", "{\"reason\":\"Synthetic statement confirms original amount.\"}"
                .getBytes(StandardCharsets.UTF_8), "test-note");
        manager();

        String original = jdbc.queryForObject("select to_char(posted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') from receipt where bank_receipt_id='missed-one'", String.class);
        PAGE.set(page("agreed-1", item("missed-one", "5000", original)));
        UUID agreed = service.create(PROJECT, "agreed", "test-agreed").runId();
        complete(agreed);
        assertThat(service.get(agreed).conflictCount()).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from exception_case where type='BANK_RECORD_CONFLICT' and bank_receipt_id='missed-one' and status='RESOLVED'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where action='EXCEPTION_NOTE' and entity_id=?
                """, Integer.class, exception)).isOne();
    }

    @Test @Order(3) void malformedIncompleteSnapshotFailsWithoutAbsenceFindings() throws Exception {
        PAGE.set("{\"snapshotId\":\"bad\",\"asOf\":\"2026-09-20T00:00:00Z\",\"items\":[],\"nextCursor\":\"loop\"}");
        manager();
        UUID run = service.create(PROJECT, "bad", "test-bad").runId();
        worker.poll();
        worker.poll();
        assertThat(service.get(run).status()).isEqualTo("FAILED");
        assertThat(service.get(run).errorCode()).isEqualTo("CURSOR_LOOP");
        assertThat(jdbc.queryForObject("""
                select count(*) from reconciliation_exception where run_id=?
                """, Integer.class, run)).isZero();
    }

    @Test @Order(4) void multiPageDuplicateAndLocalAbsenceAreComparedOnlyAfterLastPage() throws Exception {
        String asOf = Instant.now().plusSeconds(30).toString();
        String posted = Instant.now().minusSeconds(30).toString();
        String newItem = item("paged-new", "7000", posted);
        PAGE.set("{\"snapshotId\":\"paged\",\"asOf\":\"" + asOf
                + "\",\"items\":[" + newItem + "],\"nextCursor\":\"page-2\"}");
        PAGES.set(Map.of("cursor=page-2", "{\"snapshotId\":\"paged\",\"asOf\":\"" + asOf
                + "\",\"items\":[" + newItem + "],\"nextCursor\":null}"));
        manager();
        UUID run = service.create(PROJECT, "paged", "test-paged").runId();
        worker.poll();
        assertThat(service.get(run).phase()).isEqualTo("FETCH");
        assertThat(service.get(run).exceptionIds()).isEmpty();
        complete(run);
        assertThat(service.get(run).bankCount()).isOne();
        assertThat(service.get(run).recoveredCount()).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from exception_case where type='LOCAL_RECEIPT_NOT_IN_BANK'
                    and bank_receipt_id='missed-one' and status='OPEN'
                """, Integer.class)).isOne();
        PAGES.set(Map.of());
    }

    @Test @Order(5) void conflictingPageRollsBackAndTransientFetchExhaustsFiveAttempts() {
        PAGES.set(Map.of());
        String posted = Instant.now().minusSeconds(30).toString();
        PAGE.set(page("conflicting", item("same-id", "100", posted) + "," + item("same-id", "101", posted)));
        manager();
        UUID conflict = service.create(PROJECT, "conflicting", "test-conflicting").runId();
        worker.poll();
        assertThat(service.get(conflict).status()).isEqualTo("FAILED");
        assertThat(service.get(conflict).errorCode()).isEqualTo("CONFLICTING_SNAPSHOT_RECORD");
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_item where run_id=?", Integer.class, conflict)).isZero();

        PAGE.set(null);
        UUID unavailable = service.create(PROJECT, "unavailable", "test-unavailable").runId();
        for (int attempt = 1; attempt <= 5; attempt++) {
            worker.poll();
            assertThat(jdbc.queryForObject("select fetch_attempt_count from reconciliation_run where id=?", Integer.class, unavailable))
                    .isEqualTo(attempt);
            if (attempt < 5) jdbc.update("update reconciliation_run set next_attempt_at=now() where id=?", unavailable);
        }
        assertThat(service.get(unavailable).status()).isEqualTo("FAILED");
        assertThat(service.get(unavailable).errorCode()).isEqualTo("BANK_UNAVAILABLE");
        assertThat(service.get(unavailable).exceptionIds()).isEmpty();
    }

    @Test @Order(6) void expiredLeaseIsTakenOverAndProgressPersists() throws Exception {
        PAGE.set(page("takeover", item("takeover-new", "8000", Instant.now().minusSeconds(30).toString())));
        manager();
        UUID run = service.create(PROJECT, "takeover", "test-takeover").runId();
        jdbc.update("""
                update reconciliation_run set status='RUNNING', lease_owner=?, lease_version=7,
                    lease_until=now()-interval '1 second' where id=?
                """, UUID.randomUUID(), run);
        complete(run);
        assertThat(service.get(run).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select lease_version from reconciliation_run where id=?", Long.class, run)).isGreaterThan(7);
    }

    @Test @Order(7) void failedRecoveryEventProducesHistoricalCompletedWithErrors() {
        PAGE.set(page("failed-recovery", item("failed-recovery-item", "9000", Instant.now().minusSeconds(30).toString())));
        manager();
        UUID run = service.create(PROJECT, "failed-recovery", "test-failed-recovery").runId();
        worker.poll(); // complete snapshot
        worker.poll(); // enqueue
        UUID event = jdbc.queryForObject("select inbox_event_id from reconciliation_item where run_id=?", UUID.class, run);
        jdbc.update("update inbox_event set status='FAILED',last_error_code='PROCESSING_FAILED' where id=?", event);
        for (int attempt = 0; attempt < 20 && "RUNNING".equals(service.get(run).status()); attempt++) {
            worker.poll();
        }
        assertThat(service.get(run).status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(service.get(run).failedCount()).isOne();
        assertThat(service.get(run).recoveredCount()).isZero();
        assertThat(service.get(run).failedEventIds()).containsExactly(event);
        // A later manual ingestion retry cannot rewrite a finished run.
        jdbc.update("update inbox_event set status='PENDING' where id=?", event);
        assertThat(service.get(run).failedCount()).isOne();
        assertThat(service.get(run).failedEventIds()).containsExactly(event);
    }

    @Test @Order(8) void runApiEnforcesAuthenticationRolesCsrfScopeAndReplay() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/reconciliation-runs");
        String body = "{\"projectId\":\"" + PROJECT + "\"}";
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var csrfMissing = HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint)
                .header("Authorization", basic("accounts")).header("Idempotency-Key", "http-run")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrfMissing.statusCode()).isEqualTo(403);
        Client certifier = client("certifier");
        assertThat(post(certifier, endpoint, body, "http-certifier").statusCode()).isEqualTo(403);
        Client accounts = client("accounts");
        assertThat(post(accounts, endpoint, body, "").statusCode()).isEqualTo(400);
        assertThat(post(accounts, endpoint, "{}", "http-malformed").statusCode()).isEqualTo(400);
        assertThat(post(accounts, endpoint, body.replace("}", ",\"extra\":true}"), "http-extra").statusCode()).isEqualTo(400);
        assertThat(post(accounts, endpoint, body.replace(PROJECT.toString(),
                "30000000-0000-0000-0000-000000000099"), "http-scope").statusCode()).isEqualTo(404);
        var created = post(accounts, endpoint, body, "http-run");
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(created.headers().firstValue("Location")).isPresent();
        assertThat(post(accounts, endpoint, body, "http-run").body()).isEqualTo(created.body());
        var read = certifier.http().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + created.headers().firstValue("Location").orElseThrow()))
                .header("Authorization", basic("certifier")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains("countsProvisional").contains("exceptionIds");
        UUID run = UUID.fromString(created.headers().firstValue("Location").orElseThrow().replaceFirst(".*/", ""));
        PAGE.set(page("http-run", ""));
        manager();
        complete(run);
    }

    @Test @Order(9) void measureThousandReceiptWorklistAndNormalProcessing() throws Exception {
        UUID[] identities = new UUID[1000];
        for (int index = 0; index < identities.length; index++) identities[index] = UUID.randomUUID();
        jdbc.batchUpdate("""
                insert into receipt(id,source,bank_receipt_id,project_id,amount_paise,currency,posted_at,fact_hash)
                values (?, 'benchmark', ?, ?, 100, 'INR', now(), repeat('0',64))
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                statement.setObject(1, identities[index]); statement.setString(2, "benchmark-" + index);
                statement.setObject(3, PROJECT);
            }
            @Override public int getBatchSize() { return identities.length; }
        });
        jdbc.batchUpdate("""
                insert into financial_entry(kind,receipt_id,amount_paise,actor_id,reason)
                values ('RECEIPT',?,100,'10000000-0000-0000-0000-000000000004','BENCHMARK_FIXTURE')
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                statement.setObject(1, identities[index]);
            }
            @Override public int getBatchSize() { return identities.length; }
        });
        assertThat(jdbc.queryForObject("select count(*) from receipt where source='benchmark'", Integer.class)).isEqualTo(1000);

        URI uri = URI.create("http://127.0.0.1:" + port + "/api/v1/receipts?limit=100");
        HttpClient client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(uri).header("Authorization", basic("accounts")).GET().build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        List<Long> millis = new ArrayList<>();
        for (int sample = 0; sample < 10; sample++) {
            long start = System.nanoTime();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            millis.add((System.nanoTime() - start) / 1_000_000);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"reconciliation\"");
        }
        Collections.sort(millis);
        UUID event = UUID.randomUUID();
        jdbc.update("""
                insert into inbox_event(id,source,event_id,project_id,account_reference,bank_receipt_id,
                    amount_paise,currency,posted_at,canonical_hash)
                values (?,'demo-bank',?,?, 'DEMO-ACCOUNT-001',?,100,'INR',now(),repeat('0',64))
                """, event, "benchmark-event-" + event, PROJECT, "benchmark-live-" + event);
        long start = System.nanoTime();
        assertThat(receipts.processOneDueEvent()).isTrue();
        long processingMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(jdbc.queryForObject("select status from inbox_event where id=?", String.class, event)).isEqualTo("PROCESSED");
        System.out.println("DAY5_PERFORMANCE worklistMs=" + millis + " p95Ms=" + millis.get(9)
                + " processingMs=" + processingMs + " receipts=1000 pageSize=100");
    }

    @Test @Order(10) void webhookWinDoesNotInflateRecoveryAttribution() throws Exception {
        String posted = Instant.now().minusSeconds(30).toString();
        PAGE.set(page("race-snapshot", item("race-bank", "11000", posted)));
        manager();
        UUID run = service.create(PROJECT, "race", "test-race").runId();
        worker.poll(); // stage
        worker.poll(); // enqueue recovery
        UUID recovery = jdbc.queryForObject("select inbox_event_id from reconciliation_item where run_id=?", UUID.class, run);
        jdbc.update("update inbox_event set next_attempt_at=now()+interval '10 seconds' where id=?", recovery);
        String payload = "{\"eventId\":\"race-webhook\",\"receipt\":" + item("race-bank", "11000", posted) + "}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload)
                .getBytes(StandardCharsets.UTF_8)));
        UUID webhook = webhooks.accept(timestamp, signature, payload.getBytes(StandardCharsets.UTF_8), "test-race").ingestionEventId();
        assertThat(receipts.processOneDueEvent()).isTrue();
        assertThat(jdbc.queryForObject("select status from inbox_event where id=?", String.class, webhook)).isEqualTo("PROCESSED");
        jdbc.update("update inbox_event set next_attempt_at=now() where id=?", recovery);
        assertThat(receipts.processOneDueEvent()).isTrue();
        complete(run);
        assertThat(service.get(run).recoveredCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from receipt where bank_receipt_id='race-bank'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from financial_entry f join receipt r on r.id=f.receipt_id
                where r.bank_receipt_id='race-bank' and f.kind='RECEIPT'
                """, Integer.class)).isOne();
    }

    @Test @Order(11) void absenceExcludesReceiptsPostedOrRecordedAfterAsOf() throws Exception {
        Instant asOf = Instant.now().minusSeconds(5);
        localReceipt("posted-after", asOf.plusSeconds(60));
        localReceipt("recorded-after", asOf.minusSeconds(60));
        PAGE.set("{\"snapshotId\":\"boundary\",\"asOf\":\"" + asOf
                + "\",\"items\":[],\"nextCursor\":null}");
        manager();
        UUID run = service.create(PROJECT, "boundary", "test-boundary").runId();
        complete(run);
        assertThat(service.get(run).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                select count(*) from exception_case where type='LOCAL_RECEIPT_NOT_IN_BANK'
                    and bank_receipt_id in ('posted-after','recorded-after')
                """, Integer.class)).isZero();
    }

    @Test @Order(12) void laterAgreementResolvesOnlyCorrespondingBankDiscrepancy() throws Exception {
        String posted = jdbc.queryForObject("""
                select to_char(posted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
                from receipt where bank_receipt_id='missed-one'
                """, String.class);
        PAGE.set(page("local-agreement", item("missed-one", "5000", posted)));
        manager();
        UUID run = service.create(PROJECT, "local-agreement", "test-local-agreement").runId();
        complete(run);
        assertThat(jdbc.queryForObject("""
                select status from exception_case where type='LOCAL_RECEIPT_NOT_IN_BANK'
                    and bank_receipt_id='missed-one'
                """, String.class)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("""
                select count(*) from exception_case where type='UNALLOCATED_FUNDS' and
                    receipt_id=(select id from receipt where bank_receipt_id='missed-one') and status='OPEN'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where action='DISCREPANCY_RESOLVED' and
                    entity_id=(select id from exception_case where type='LOCAL_RECEIPT_NOT_IN_BANK'
                        and bank_receipt_id='missed-one')
                """, Integer.class)).isOne();
    }

    @Test @Order(13) void changedSnapshotMetadataAndMalformedMoneyCannotStartComparison() {
        String asOf = Instant.now().toString();
        PAGE.set("{\"snapshotId\":\"original\",\"asOf\":\"" + asOf
                + "\",\"items\":[],\"nextCursor\":\"page-2\"}");
        PAGES.set(Map.of("cursor=page-2", "{\"snapshotId\":\"changed\",\"asOf\":\"" + asOf
                + "\",\"items\":[],\"nextCursor\":null}"));
        manager();
        UUID changed = service.create(PROJECT, "metadata-changed", "test-metadata-changed").runId();
        worker.poll();
        worker.poll();
        assertThat(service.get(changed).status()).isEqualTo("FAILED");
        assertThat(service.get(changed).errorCode()).isEqualTo("INCONSISTENT_SNAPSHOT");
        assertThat(service.get(changed).exceptionIds()).isEmpty();

        PAGES.set(Map.of());
        PAGE.set(page("malformed-money", item("invalid-money", "1.0", Instant.now().minusSeconds(5).toString())));
        UUID malformed = service.create(PROJECT, "invalid-money", "test-invalid-money").runId();
        worker.poll();
        assertThat(service.get(malformed).status()).isEqualTo("FAILED");
        assertThat(service.get(malformed).errorCode()).isEqualTo("INVALID_SNAPSHOT");
        assertThat(service.get(malformed).exceptionIds()).isEmpty();
    }

    @Test @Order(14) void staleWorkerCannotCommitFetchedPageAfterLeaseTakeover() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var client = mock(dev.sakshi.milestoneledger.reconciliation.BankSnapshotClient.class);
        when(client.fetch(null)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test fetch was not released");
            return new dev.sakshi.milestoneledger.reconciliation.BankSnapshotClient.Page(
                    "stale", Instant.now(), List.of(), null);
        });
        ReconciliationWorker oldWorker = new ReconciliationWorker(jdbc, transactions, client, inbox);
        PAGE.set(page("takeover-after-stale", ""));
        manager();
        UUID run = service.create(PROJECT, "stale-worker", "test-stale-worker").runId();
        Thread thread = new Thread(oldWorker::poll);
        thread.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        jdbc.update("""
                update reconciliation_run set lease_owner=?, lease_version=lease_version+1,
                    lease_until=now()-interval '1 second' where id=?
                """, UUID.randomUUID(), run);
        release.countDown();
        thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(jdbc.queryForObject("select page_count from reconciliation_run where id=?", Integer.class, run)).isZero();
        complete(run);
        assertThat(service.get(run).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                select status from exception_case where type='LOCAL_RECEIPT_NOT_IN_BANK'
                    and bank_receipt_id='missed-one'
                """, String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where action='DISCREPANCY_REOPENED' and
                    entity_id=(select id from exception_case where type='LOCAL_RECEIPT_NOT_IN_BANK'
                        and bank_receipt_id='missed-one')
                """, Integer.class)).isOne();
    }

    @Test @Order(15) void absenceScanCommitsBoundedBatchesAndAnotherWorkerResumes() throws Exception {
        Instant posted = Instant.now().minusSeconds(20);
        for (int index = 0; index < 150; index++) localReceipt("batch-local-" + index, posted);
        PAGE.set(page("batch-absence", ""));
        manager();
        UUID run = service.create(PROJECT, "batch-absence", "test-batch-absence").runId();
        worker.poll(); // fetch complete
        worker.poll(); // compare complete
        worker.poll(); // first absence batch
        assertThat(service.get(run).phase()).isEqualTo("ABSENCE");
        assertThat(jdbc.queryForObject("select absence_cursor from reconciliation_run where id=?", UUID.class, run))
                .isNotNull();
        ReconciliationWorker replacement = new ReconciliationWorker(jdbc, transactions, snapshotClient, inbox);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !"COMPLETED".equals(service.get(run).status())) replacement.poll();
        assertThat(service.get(run).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                select count(*) from reconciliation_exception x join exception_case e on e.id=x.exception_id
                where x.run_id=? and e.type='LOCAL_RECEIPT_NOT_IN_BANK' and e.bank_receipt_id like 'batch-local-%'
                """, Integer.class, run)).isEqualTo(150);
    }

    @Test @Order(16) void applicationInstancesResumePagesComparisonAndRecoveryWait() throws Exception {
        String asOf = Instant.now().plusSeconds(30).toString();
        String posted = Instant.now().minusSeconds(30).toString();
        PAGE.set("{\"snapshotId\":\"restart-pages\",\"asOf\":\"" + asOf
                + "\",\"items\":[" + item("restart-a", "12000", posted)
                + "],\"nextCursor\":\"page-2\"}");
        PAGES.set(Map.of("cursor=page-2", "{\"snapshotId\":\"restart-pages\",\"asOf\":\"" + asOf
                + "\",\"items\":[" + item("restart-b", "13000", posted)
                + "],\"nextCursor\":null}"));
        manager();
        UUID run = service.create(PROJECT, "restart-phases", "test-restart-phases").runId();
        worker.poll(); // first page is committed before a new application instance starts
        assertThat(service.get(run).bankCount()).isOne();
        try (ConfigurableApplicationContext restarted = freshApplication()) {
            var resumed = restarted.getBean(ReconciliationWorker.class);
            resumed.poll(); // second page
            resumed.poll(); // enqueue first item
        }
        assertThat(service.get(run).bankCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_item where run_id=? and outcome='ENQUEUED'",
                Integer.class, run)).isOne();
        try (ConfigurableApplicationContext restarted = freshApplication()) {
            var resumed = restarted.getBean(ReconciliationWorker.class);
            for (int attempt = 0; attempt < 20 && !"WAIT".equals(service.get(run).phase()); attempt++) resumed.poll();
        }
        assertThat(service.get(run).phase()).isEqualTo("WAIT");
        for (int attempt = 0; attempt < 10 && jdbc.queryForObject("""
                select count(*) from reconciliation_item i join inbox_event e on e.id=i.inbox_event_id
                where i.run_id=? and e.status='PENDING'
                """, Integer.class, run) > 0; attempt++) receipts.processOneDueEvent();
        try (ConfigurableApplicationContext restarted = freshApplication()) {
            restarted.getBean(ReconciliationWorker.class).poll();
        }
        assertThat(service.get(run).status()).isEqualTo("COMPLETED");
        assertThat(service.get(run).recoveredCount()).isEqualTo(2);
        PAGES.set(Map.of());
    }

    @Test @Order(17) void concurrentStartsLeaveExactlyOneActiveRun() throws Exception {
        PAGE.set(page("concurrent-start", ""));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Object>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                String key = "concurrent-start-" + index;
                results.add(pool.submit(() -> {
                    manager();
                    if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
                    try { return service.create(PROJECT, key, "test-concurrent-start"); }
                    catch (ApiException exception) { return exception.code(); }
                }));
            }
            gate.countDown();
            List<Object> outcomes = List.of(results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(ReconciliationService.Accepted.class::isInstance).count()).isOne();
            assertThat(outcomes).contains("RECONCILIATION_ALREADY_RUNNING");
            UUID run = outcomes.stream().filter(ReconciliationService.Accepted.class::isInstance)
                    .map(ReconciliationService.Accepted.class::cast).findFirst().orElseThrow().runId();
            manager();
            complete(run);
        }
    }

    @Test @Order(18) void expiredSnapshotFailsWithoutAbsenceComparison() {
        PAGE.set("{\"snapshotId\":\"expires\",\"asOf\":\"" + Instant.now()
                + "\",\"items\":[],\"nextCursor\":\"expired\"}");
        CODES.set(Map.of("cursor=expired", 410));
        manager();
        UUID run = service.create(PROJECT, "expired-snapshot", "test-expired-snapshot").runId();
        worker.poll();
        worker.poll();
        assertThat(service.get(run).status()).isEqualTo("FAILED");
        assertThat(service.get(run).errorCode()).isEqualTo("SNAPSHOT_EXPIRED");
        assertThat(service.get(run).exceptionIds()).isEmpty();
        CODES.set(Map.of());
    }

    @Test @Order(19) void invalidIdentityCurrencyReferenceAndTimeFailSafely() {
        String posted = Instant.now().minusSeconds(10).toString();
        String valid = item("bad-facts", "100", posted);
        List<String> invalidItems = List.of(
                valid.replace("bad-facts", " bad-facts "),
                valid.replace("\"INR\"", "\"USD\""),
                valid.replace("\"demandReference\":null", "\"demandReference\":\" padded\""),
                item("future-posted", "100", Instant.now().plusSeconds(120).toString()));
        manager();
        for (int index = 0; index < invalidItems.size(); index++) {
            PAGE.set(page("invalid-" + index, invalidItems.get(index)));
            UUID run = service.create(PROJECT, "invalid-facts-" + index, "test-invalid-facts").runId();
            worker.poll();
            assertThat(service.get(run).status()).isEqualTo("FAILED");
            assertThat(service.get(run).errorCode()).isEqualTo("INVALID_SNAPSHOT");
            assertThat(service.get(run).exceptionIds()).isEmpty();
        }
    }

    @Test @Order(20) void databaseUnsafeSnapshotTextFailsAndReleasesTheActiveRun() throws Exception {
        String posted = Instant.now().minusSeconds(10).toString();
        String escapedNul = "bad" + "\\" + "u0000" + "receipt";
        PAGE.set(page("nul-identity", item(escapedNul, "100", posted)));
        manager();
        UUID invalid = service.create(PROJECT, "nul-identity", "test-nul").runId();
        worker.poll();
        assertThat(service.get(invalid).status()).isEqualTo("FAILED");
        assertThat(service.get(invalid).errorCode()).isEqualTo("INVALID_SNAPSHOT");

        var malformedClient = mock(BankSnapshotClient.class);
        when(malformedClient.fetch(null)).thenReturn(new BankSnapshotClient.Page(
                "bad-staging", Instant.now(), List.of(new BankSnapshotClient.Item(
                "bad\0receipt", 100, "INR", null, Instant.now().minusSeconds(10))), null));
        UUID staging = service.create(PROJECT, "nul-staging", "test-nul-staging").runId();
        new ReconciliationWorker(jdbc, transactions, malformedClient, inbox).poll();
        assertThat(service.get(staging).status()).isEqualTo("FAILED");
        assertThat(service.get(staging).errorCode()).isEqualTo("INVALID_SNAPSHOT");

        PAGE.set(page("after-invalid", ""));
        UUID replacement = service.create(PROJECT, "after-invalid", "test-after-invalid").runId();
        complete(replacement);
        assertThat(service.get(replacement).status()).isEqualTo("COMPLETED");
    }

    @Test @Order(21) void olderSnapshotCannotResolveAConflictObservedAfterItsAsOf() throws Exception {
        String bankId = "late-conflict-" + UUID.randomUUID();
        Instant posted = Instant.now().minusSeconds(60);
        localReceipt(bankId, posted);
        Instant asOf = Instant.now().minusSeconds(10);
        PAGE.set("{\"snapshotId\":\"older-agreement\",\"asOf\":\"" + asOf
                + "\",\"items\":[" + item(bankId, "100", posted.toString()) + "],\"nextCursor\":null}");
        manager();
        UUID older = service.create(PROJECT, "older-agreement", "test-older-agreement").runId();
        worker.poll(); // stage the complete, older bank snapshot
        jdbc.update("""
                insert into exception_case(type,project_id,receipt_id,source,bank_receipt_id,dedupe_key,reason_code)
                values ('BANK_RECORD_CONFLICT',?,(select id from receipt where bank_receipt_id=?),
                    'demo-bank',?,?,'FACTS_CHANGED')
                """, PROJECT, bankId, bankId, "BANK_RECORD_CONFLICT:demo-bank:" + bankId);
        complete(older);
        assertThat(jdbc.queryForObject("select status from exception_case where dedupe_key=?", String.class,
                "BANK_RECORD_CONFLICT:demo-bank:" + bankId)).isEqualTo("OPEN");

        PAGE.set(page("later-agreement", item(bankId, "100", posted.toString())));
        UUID later = service.create(PROJECT, "later-agreement", "test-later-agreement").runId();
        complete(later);
        assertThat(jdbc.queryForObject("select status from exception_case where dedupe_key=?", String.class,
                "BANK_RECORD_CONFLICT:demo-bank:" + bankId)).isEqualTo("RESOLVED");
    }

    @Test @Order(22) void finalizationWaitsForConcurrentManagerRetry() throws Exception {
        PAGE.set(page("retry-finalization", item("retry-finalization-" + UUID.randomUUID(), "100",
                Instant.now().minusSeconds(10).toString())));
        manager();
        UUID run = service.create(PROJECT, "retry-finalization", "test-retry-finalization").runId();
        for (int attempt = 0; attempt < 20 && !"WAIT".equals(service.get(run).phase()); attempt++) worker.poll();
        assertThat(service.get(run).phase()).isEqualTo("WAIT");
        UUID event = jdbc.queryForObject("select inbox_event_id from reconciliation_item where run_id=?", UUID.class, run);
        jdbc.update("update inbox_event set status='FAILED',last_error_code='PROCESSING_FAILED' where id=?", event);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var retry = pool.submit(() -> {
                manager();
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    webhooks.retry(event, "Synthetic retry during finalization", "retry-finalization-key", "test-retry");
                    updated.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("retry was not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
            });
            try {
                assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
                var finalization = pool.submit(worker::poll);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && jdbc.queryForObject(
                        "select lease_owner from reconciliation_run where id=?", UUID.class, run) == null) {
                    Thread.sleep(10);
                }
                assertThat(jdbc.queryForObject("select lease_owner from reconciliation_run where id=?", UUID.class, run))
                        .isNotNull();
                assertThatThrownBy(() -> finalization.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                retry.get(5, TimeUnit.SECONDS);
                finalization.get(5, TimeUnit.SECONDS);
                assertThat(service.get(run).status()).isEqualTo("RUNNING");
                assertThat(service.get(run).phase()).isEqualTo("WAIT");
            } finally { release.countDown(); }
        }
        jdbc.update("update inbox_event set status='FAILED',last_error_code='PROCESSING_FAILED' where id=?", event);
        // The delayed WAIT step is due again after its persisted retry interval.
        jdbc.update("update reconciliation_run set next_attempt_at=now() where id=?", run);
        worker.poll();
        assertThat(service.get(run).status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(service.get(run).failedCount()).isOne();
    }

    @Test @Order(23) void oversizedBankResponseFailsWithoutComparison() {
        PAGE.set("{\"snapshotId\":\"large\",\"asOf\":\"" + Instant.now()
                + "\",\"items\":[],\"nextCursor\":null,\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}");
        manager();
        UUID run = service.create(PROJECT, "large-response", "test-large-response").runId();
        worker.poll();
        assertThat(service.get(run).status()).isEqualTo("FAILED");
        assertThat(service.get(run).errorCode()).isEqualTo("INVALID_SNAPSHOT");
        assertThat(service.get(run).exceptionIds()).isEmpty();
    }

    private ConfigurableApplicationContext freshApplication() {
        return new SpringApplicationBuilder(MilestoneLedgerApplication.class).web(WebApplicationType.SERVLET)
                .properties(Map.ofEntries(
                        Map.entry("server.port", "0"),
                        Map.entry("DB_URL", POSTGRES.getJdbcUrl()),
                        Map.entry("DB_USERNAME", "milestone_app"),
                        Map.entry("DB_PASSWORD", "test-app-password"),
                        Map.entry("DB_MIGRATION_URL", POSTGRES.getJdbcUrl()),
                        Map.entry("DB_MIGRATION_USERNAME", "test_owner"),
                        Map.entry("DB_MIGRATION_PASSWORD", "test-owner-password"),
                        Map.entry("APP_SETUP_PROJECT_ID", PROJECT.toString()),
                        Map.entry("BANK_WEBHOOK_PROJECT_ID", PROJECT.toString()),
                        Map.entry("BANK_WEBHOOK_SIGNING_SECRET", "test-webhook-secret"),
                        Map.entry("BANK_BASE_URL", "http://127.0.0.1:" + SERVER.getAddress().getPort()),
                        Map.entry("APP_SECURITY_CERTIFIER_PASSWORD_HASH", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                        Map.entry("APP_SECURITY_ACCOUNTS_PASSWORD_HASH", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                        Map.entry("APP_SECURITY_MANAGER_PASSWORD_HASH", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                        Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
                        Map.entry("spring.datasource.username", "milestone_app"),
                        Map.entry("spring.datasource.password", "test-app-password"),
                        Map.entry("spring.flyway.url", POSTGRES.getJdbcUrl()),
                        Map.entry("spring.flyway.user", "test_owner"),
                        Map.entry("spring.flyway.password", "test-owner-password"),
                        Map.entry("app.setup.project-id", PROJECT.toString()),
                        Map.entry("app.bank.webhook.project-id", PROJECT.toString()),
                        Map.entry("app.bank.webhook.signing-secret", "test-webhook-secret"),
                        Map.entry("app.bank.base-url", "http://127.0.0.1:" + SERVER.getAddress().getPort()),
                        Map.entry("app.security.certifier-password-hash", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                        Map.entry("app.security.accounts-password-hash", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                        Map.entry("app.security.manager-password-hash", "$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu"),
                        Map.entry("app.receipt-worker.initial-delay-ms", "60000"),
                        Map.entry("app.reconciliation-worker.initial-delay-ms", "60000"))).run();
    }

    private void localReceipt(String bankId, Instant posted) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into receipt(id,source,bank_receipt_id,project_id,amount_paise,currency,
                    posted_at,fact_hash) values (?,'demo-bank',?,?,100,'INR',?,?)
                """, id, bankId, PROJECT, java.sql.Timestamp.from(posted),
                ReceiptFacts.hash("demo-bank", PROJECT, "DEMO-ACCOUNT-001", bankId, 100, "INR", null, posted));
        jdbc.update("""
                insert into financial_entry(kind,receipt_id,amount_paise,actor_id,reason)
                values ('RECEIPT',?,100,'10000000-0000-0000-0000-000000000004','BOUNDARY_FIXTURE')
                """, id);
    }

    private Client client(String role) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/csrf-token"))
                .header("Authorization", basic(role)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = response.body().replaceFirst(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");
        return new Client(http, role, token);
    }
    private HttpResponse<String> post(Client client, URI endpoint, String body, String key) throws Exception {
        return client.http().send(HttpRequest.newBuilder(endpoint).header("Authorization", basic(client.role()))
                .header("Idempotency-Key", key).header("X-XSRF-TOKEN", client.csrf())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static String basic(String role) {
        return "Basic " + Base64.getEncoder().encodeToString((role + ":password").getBytes(StandardCharsets.UTF_8));
    }
    private record Client(HttpClient http, String role, String csrf) { }

    private void complete(UUID run) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            worker.poll();
            receipts.processOneDueEvent();
            var state = service.get(run);
            if (List.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED").contains(state.status())) return;
            Thread.sleep(25);
        }
        throw new AssertionError("reconciliation did not finish within 10 seconds");
    }

    private static String page(String snapshotId, String items) {
        return "{\"snapshotId\":\"" + snapshotId + "\",\"asOf\":\"" + Instant.now().plusSeconds(30)
                + "\",\"items\":[" + items + "],\"nextCursor\":null}";
    }
    private static String item(String id, String amount, String posted) {
        return "{\"bankReceiptId\":\"" + id + "\",\"amountPaise\":\"" + amount
                + "\",\"currency\":\"INR\",\"demandReference\":null,\"postedAt\":\"" + posted + "\"}";
    }
    private static void manager() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }
    private static void accounts() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("accounts", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTS"))));
    }
}
