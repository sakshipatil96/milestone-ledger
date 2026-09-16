package dev.sakshi.milestoneledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

class JsonAccessDeniedHandlerTest {
    @Test
    void returnsCorrelatedForbiddenEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "forbidden-test-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsonAccessDeniedHandler(new ObjectMapper()).handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN").contains("forbidden-test-id");
    }
}
