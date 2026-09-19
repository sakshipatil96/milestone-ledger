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
}
