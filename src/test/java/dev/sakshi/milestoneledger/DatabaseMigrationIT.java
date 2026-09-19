package dev.sakshi.milestoneledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;

import dev.sakshi.milestoneledger.setup.SetupQueryService;
import dev.sakshi.milestoneledger.shared.validation.ValidationRules;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
class DatabaseMigrationIT {
    private static final String PROJECT_ID = "30000000-0000-0000-0000-000000000001";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("milestone_ledger_test").withUsername("test_owner").withPassword("test-owner-password")
            .withInitScript("db/test/create-runtime-role.sql");

    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", () -> "milestone_app");
        r.add("spring.datasource.password", () -> "test-app-password"); r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername); r.add("spring.flyway.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SetupQueryService setupQueryService;
    @LocalServerPort int serverPort;

    @Test void migrationsSeedOnceAndRuntimeRoleCanOnlyReadSetup() throws Exception {
        assertThat(jdbcTemplate.queryForObject("select count(*) from project where code = ?", Integer.class, "DEMO-RIVER-001")).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from milestone where project_id = ?::uuid", Integer.class, PROJECT_ID)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from demand", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update("insert into client (name) values (?)", "blocked")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update project set name = name where id = ?::uuid", PROJECT_ID)).isInstanceOf(Exception.class);
        try (Connection owner = ownerConnection()) { owner.createStatement().executeUpdate("insert into client (name) values ('Restart survival fixture')"); }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        try (Connection owner = ownerConnection(); var s = owner.createStatement(); var rows = s.executeQuery("select count(*) from client where name = 'Restart survival fixture'")) {
            rows.next(); assertThat(rows.getInt(1)).isOne();
        }
    }

    @Test void allRolesReadSetupAndResponsesFollowContract() throws Exception {
        for (String role : List.of("certifier", "accounts", "manager")) {
            HttpResponse<String> projects = get("/api/v1/projects?limit=1", role, "password", null);
            assertThat(projects.statusCode()).isEqualTo(200);
            assertThat(projects.body()).contains("\"items\"").contains("DEMO-RIVER-001").contains("Northstar Public Works Authority").contains("\"nextCursor\":null");
            HttpResponse<String> milestones = get("/api/v1/projects/" + PROJECT_ID + "/milestones", role, "password", null);
            assertThat(milestones.statusCode()).isEqualTo(200);
            assertThat(milestones.body()).contains("\"status\":\"SCHEDULED\"").contains("\"sequence\":1").contains("\"certifiedAmountPaise\":null");
        }
    }

    @Test void invalidAndUnauthorizedRequestsHaveCorrelatedPredictableErrors() throws Exception {
        HttpResponse<String> missing = get("/api/v1/projects", null, null, null);
        assertThat(missing.statusCode()).isEqualTo(401); assertThat(missing.headers().firstValue("WWW-Authenticate")).isPresent(); assertThat(missing.body()).contains("UNAUTHENTICATED").contains("requestId");
        HttpResponse<String> invalid = get("/api/v1/projects/not-a-uuid/milestones", "certifier", "password", null);
        assertThat(invalid.statusCode()).isEqualTo(400); assertThat(invalid.body()).contains("VALIDATION_ERROR");
        HttpResponse<String> scoped = get("/api/v1/projects/30000000-0000-0000-0000-000000000099/milestones", "certifier", "password", null);
        assertThat(scoped.statusCode()).isEqualTo(404); assertThat(scoped.body()).contains("NOT_FOUND");
        assertThat(get("/api/v1/projects?cursor=bad", "certifier", "password", null).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/projects", "certifier", "password", "safe-request-id").headers().firstValue("X-Request-Id")).contains("safe-request-id");
        assertThat(get("/api/v1/projects", "certifier", "password", "unsafe value").headers().firstValue("X-Request-Id").orElseThrow()).doesNotContain("unsafe value");
    }

    @Test void paginationCursorsProduceStablePagesAndValidEmptyPages() throws Exception {
        HttpResponse<String> first = get("/api/v1/projects/" + PROJECT_ID + "/milestones?limit=1", "certifier", "password", null);
        assertThat(first.statusCode()).isEqualTo(200); assertThat(first.body()).contains("\"sequence\":1").contains("nextCursor");
        String cursor = first.body().replaceFirst(".*\\\"nextCursor\\\":\\\"([^\\\"]+)\\\".*", "$1");
        HttpResponse<String> second = get("/api/v1/projects/" + PROJECT_ID + "/milestones?limit=1&cursor=" + cursor, "certifier", "password", null);
        assertThat(second.statusCode()).isEqualTo(200); assertThat(second.body()).contains("\"sequence\":2").contains("\"nextCursor\":null");
        String futureCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "2999-01-01T00:00:00Z|ffffffff-ffff-ffff-ffff-ffffffffffff".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> empty = get("/api/v1/projects/" + PROJECT_ID + "/milestones?cursor=" + futureCursor, "certifier", "password", null);
        assertThat(empty.statusCode()).isEqualTo(200); assertThat(empty.body()).isEqualTo("{\"items\":[],\"nextCursor\":null}");
    }

    @Test void serviceAuthorizationDoesNotGrantUnknownRole() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("other", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OTHER"))));
        try { assertThatThrownBy(() -> setupQueryService.projects(null, null)).isInstanceOf(AccessDeniedException.class); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void validationRulesRejectUnsafeMoneyAndReferences() {
        assertThat(ValidationRules.positivePaise("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> ValidationRules.positivePaise("0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ValidationRules.positivePaise("1.0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ValidationRules.positivePaise("9223372036854775808")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ValidationRules.addPaise(Long.MAX_VALUE, 1)).isInstanceOf(RuntimeException.class);
        assertThat(ValidationRules.exactReference("Ref-A")).isEqualTo("Ref-A");
        assertThatThrownBy(() -> ValidationRules.exactReference(" Ref-A")).isInstanceOf(RuntimeException.class);
    }

    private HttpResponse<String> get(String path, String username, String password, String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path)).GET();
        if (username != null) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        if (requestId != null) request.header("X-Request-Id", requestId);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private Connection ownerConnection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }

}
