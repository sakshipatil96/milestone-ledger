package dev.sakshi.milestoneledger.payments;

import java.util.List;
import java.util.UUID;

import dev.sakshi.milestoneledger.shared.web.CorrelationIdFilter;
import dev.sakshi.milestoneledger.shared.web.KeysetPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentQueryService service;
    private final PaymentMutationService mutations;

    public PaymentController(PaymentQueryService service, PaymentMutationService mutations) {
        this.service = service;
        this.mutations = mutations;
    }

    @GetMapping("/receipts/{receiptId}")
    public PaymentResponses.Receipt receipt(@PathVariable UUID receiptId,
                                             @RequestParam(required = false) UUID projectId) {
        return service.receipt(receiptId, projectId);
    }

    @GetMapping("/receipts")
    public PaymentResponses.Worklist<PaymentResponses.Receipt> receipts(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.receipts(status, projectId, limit, cursor);
    }

    @GetMapping("/financial-entries")
    public KeysetPage.Response<PaymentResponses.Entry> entries(
            @RequestParam(required = false) UUID receiptId,
            @RequestParam(required = false) UUID demandId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.entries(receiptId, demandId, projectId, limit, cursor);
    }

    @GetMapping("/exceptions")
    public KeysetPage.Response<PaymentResponses.ExceptionCase> exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.exceptions(status, projectId, limit, cursor);
    }

    @PostMapping("/receipts/{receiptId}/allocations")
    public ResponseEntity<PaymentMutationResponses.Allocation> allocate(
            @PathVariable UUID receiptId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                mutations.allocate(receiptId, key, body, CorrelationIdFilter.requestId(request)));
    }

    @PostMapping("/exceptions/{exceptionId}/notes")
    public ResponseEntity<PaymentMutationResponses.Note> note(
            @PathVariable UUID exceptionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                mutations.addNote(exceptionId, key, body, CorrelationIdFilter.requestId(request)));
    }

    @GetMapping("/exceptions/{exceptionId}/notes")
    public KeysetPage.Response<PaymentMutationResponses.Note> notes(
            @PathVariable UUID exceptionId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return mutations.notes(exceptionId, limit, cursor);
    }
}
