package dev.sakshi.milestoneledger.security;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/csrf-token")
public class CsrfTokenController {
    @GetMapping
    public CsrfTokenResponse token(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }

    public record CsrfTokenResponse(String headerName, String token) {
    }
}
