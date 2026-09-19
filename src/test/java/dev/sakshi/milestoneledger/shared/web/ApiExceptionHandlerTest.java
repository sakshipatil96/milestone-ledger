package dev.sakshi.milestoneledger.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    @Test void unavailableDatabaseHasTheCorrelatedServiceUnavailableEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.REQUEST_ID_ATTRIBUTE, "request-123");

        var response = new ApiExceptionHandler().database(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.getBody().error().requestId()).isEqualTo("request-123");
    }
}
