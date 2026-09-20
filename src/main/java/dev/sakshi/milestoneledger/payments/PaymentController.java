package dev.sakshi.milestoneledger.payments;

import java.util.UUID;
import dev.sakshi.milestoneledger.shared.web.KeysetPage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentQueryService service;
    public PaymentController(PaymentQueryService service) { this.service = service; }
    @GetMapping("/receipts/{receiptId}") public PaymentResponses.Receipt receipt(@PathVariable UUID receiptId) { return service.receipt(receiptId); }
    @GetMapping("/financial-entries")
    public KeysetPage.Response<PaymentResponses.Entry> entries(@RequestParam(required = false) UUID receiptId,
            @RequestParam(required = false) UUID demandId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.entries(receiptId, demandId, limit, cursor);
    }
    @GetMapping("/exceptions")
    public KeysetPage.Response<PaymentResponses.ExceptionCase> exceptions(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return service.exceptions(status, limit, cursor);
    }
}
