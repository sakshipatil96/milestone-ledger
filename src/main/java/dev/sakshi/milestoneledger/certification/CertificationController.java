package dev.sakshi.milestoneledger.certification;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import dev.sakshi.milestoneledger.shared.web.CorrelationIdFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/milestones")
public class CertificationController {
    private final CertificationService service;

    public CertificationController(CertificationService service) {
        this.service = service;
    }

    @PostMapping("/{milestoneId}/certification")
    public ResponseEntity<CertificationResponse> certify(
            @PathVariable UUID milestoneId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body,
            HttpServletRequest request) {
        var result = service.certify(milestoneId, idempotencyKey, body, CorrelationIdFilter.requestId(request));
        return ResponseEntity.status(result.replayed() ? result.status() : HttpStatus.CREATED).body(result.response());
    }
}
