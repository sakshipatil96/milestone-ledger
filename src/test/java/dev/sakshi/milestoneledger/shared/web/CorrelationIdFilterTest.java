package dev.sakshi.milestoneledger.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
}
