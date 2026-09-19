package dev.sakshi.milestoneledger.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

class BankWebhookServiceTest {
    private static final String SECRET = "test-webhook-secret";

    @Test void unavailableDatabasePreventsWebhookAcceptance() throws Exception {
        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        BankWebhookService service = new BankWebhookService(new JdbcTemplate(unavailableDataSource()), JsonMapper.builder().build(),
                new BankWebhookProperties("demo-bank", UUID.fromString("30000000-0000-0000-0000-000000000001"),
                        "DEMO-ACCOUNT-001", SECRET), clock);
        String payload = "{\"eventId\":\"evt-db-offline\",\"receipt\":{\"bankReceiptId\":\"bank-db-offline\",\"amountPaise\":\"1\",\"currency\":\"INR\",\"demandReference\":\"DEM-UNKNOWN\",\"postedAt\":\"2026-09-19T10:00:00Z\"}}";
        String timestamp = Long.toString(now.getEpochSecond());

        assertThatThrownBy(() -> service.accept(timestamp, signature(timestamp, payload), payload.getBytes(StandardCharsets.UTF_8), "request-id"))
                .isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    private static DataSource unavailableDataSource() {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException { throw new SQLException("database unavailable"); }
            @Override public Connection getConnection(String username, String password) throws SQLException { throw new SQLException("database unavailable"); }
            @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("unsupported"); }
            @Override public boolean isWrapperFor(Class<?> type) { return false; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        };
    }

    private static String signature(String timestamp, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
}
