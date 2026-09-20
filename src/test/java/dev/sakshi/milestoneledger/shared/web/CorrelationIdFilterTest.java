package dev.sakshi.milestoneledger.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    @Test
    void suppressesOnlySuccessfulHealthProbeCompletionLogs() {
        MockHttpServletRequest readiness = new MockHttpServletRequest("GET", "/api/v1/health/ready");
        MockHttpServletResponse success = new MockHttpServletResponse();
        success.setStatus(200);
        assertThat(CorrelationIdFilter.isSuccessfulHealthProbe(readiness, success)).isTrue();

        MockHttpServletResponse unavailable = new MockHttpServletResponse();
        unavailable.setStatus(503);
        assertThat(CorrelationIdFilter.isSuccessfulHealthProbe(readiness, unavailable)).isFalse();
        assertThat(CorrelationIdFilter.isSuccessfulHealthProbe(
                new MockHttpServletRequest("GET", "/api/v1/projects"), success)).isFalse();
    }

    @Test
    void successfulCollectionReadsAreQuietButFailuresLogOnlySafeTimingAndPath() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            CorrelationIdFilter filter = new CorrelationIdFilter("test-version");
            MockHttpServletRequest success = new MockHttpServletRequest("GET", "/api/v1/demands");
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilterInternal(success, ok, (request, response) -> ((MockHttpServletResponse) response).setStatus(200));
            assertThat(captured.list).isEmpty();

            MockHttpServletRequest failure = new MockHttpServletRequest("GET", "/api/v1/receipts");
            failure.setQueryString("status=SECRET_QUERY");
            failure.addHeader("Authorization", "SECRET_AUTHORIZATION");
            failure.addHeader("X-Request-Id", "safe-request-id");
            MockHttpServletResponse unavailable = new MockHttpServletResponse();
            filter.doFilterInternal(failure, unavailable,
                    (request, response) -> ((MockHttpServletResponse) response).setStatus(503));
            assertThat(captured.list).hasSize(1);
            ILoggingEvent event = captured.list.getFirst();
            assertThat(event.getFormattedMessage()).isEqualTo("http_request_completed");
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", "safe-request-id");
            String fields = event.getKeyValuePairs().toString();
            assertThat(fields).contains("httpPath=\"/api/v1/receipts\"", "httpStatus=\"503\"", "durationMs=");
            assertThat(fields + event.getFormattedMessage()).doesNotContain("SECRET_QUERY", "SECRET_AUTHORIZATION");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
