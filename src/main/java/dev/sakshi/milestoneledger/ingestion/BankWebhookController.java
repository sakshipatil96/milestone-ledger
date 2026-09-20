package dev.sakshi.milestoneledger.ingestion;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import dev.sakshi.milestoneledger.shared.web.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1")
public class BankWebhookController {
    private final BankWebhookService service;

    public BankWebhookController(BankWebhookService service) {
        this.service = service;
    }

    @PostMapping("/webhooks/bank")
    public ResponseEntity<IngestionResponse.Accepted> accept(
            @RequestHeader(value = "X-Bank-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Bank-Signature", required = false) String signature,
            @RequestBody byte[] body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.accept(timestamp, signature, body, CorrelationIdFilter.requestId(request)));
    }

    @GetMapping("/ingestion-events/{eventId}")
    public IngestionResponse.Detail event(@PathVariable UUID eventId) {
        return service.event(eventId);
    }

    @GetMapping("/ingestion-events")
    public dev.sakshi.milestoneledger.shared.web.KeysetPage.Response<IngestionResponse.Detail> events(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.events(status, limit, cursor);
    }

    @PostMapping("/ingestion-events/{eventId}/retry")
    public ResponseEntity<IngestionResponse.RetryAccepted> retry(@PathVariable UUID eventId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RetryRequest request, HttpServletRequest servletRequest) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || !idempotencyKey.equals(idempotencyKey.strip()) || idempotencyKey.length() > 200) {
            throw new dev.sakshi.milestoneledger.shared.web.ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        }
        return ResponseEntity.accepted().body(service.retry(eventId, request.reason(), idempotencyKey, CorrelationIdFilter.requestId(servletRequest)));
    }

    public record RetryRequest(String reason) { }
}
