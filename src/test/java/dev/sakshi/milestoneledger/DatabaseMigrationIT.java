package dev.sakshi.milestoneledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.security.certifier-password=test-certifier-password",
            "app.security.accounts-password=test-accounts-password",
            "app.security.manager-password=test-manager-password"
        })
class DatabaseMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("milestone_ledger_test")
            .withUsername("test_owner")
            .withPassword("test-owner-password")
            .withInitScript("db/test/create-runtime-role.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @LocalServerPort
    int serverPort;

    @Test
    void migrationsSeedSyntheticProjectAndExposeReadiness() throws Exception {
        Integer projectCount = jdbcTemplate.queryForObject(
                "select count(*) from project where code = ?",
                Integer.class,
                "DEMO-RIVER-001");
        Integer milestoneCount = jdbcTemplate.queryForObject(
                "select count(*) from milestone where project_id = ?::uuid",
                Integer.class,
                "30000000-0000-0000-0000-000000000001");

        assertThat(projectCount).isOne();
        assertThat(milestoneCount).isEqualTo(2);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + serverPort + "/actuator/health/readiness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void operationalDetailsRequireAnAuthenticatedDemoIdentity() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI infoEndpoint = URI.create("http://localhost:" + serverPort + "/actuator/info");

        HttpResponse<String> unauthenticatedResponse = client.send(
                HttpRequest.newBuilder(infoEndpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(unauthenticatedResponse.statusCode()).isEqualTo(401);

        String credentials = Base64.getEncoder().encodeToString(
                "accounts:test-accounts-password".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> authenticatedResponse = client.send(
                HttpRequest.newBuilder(infoEndpoint)
                        .header("Authorization", "Basic " + credentials)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(authenticatedResponse.statusCode()).isEqualTo(200);
        assertThat(authenticatedResponse.body()).contains("milestone-ledger");
    }
}
