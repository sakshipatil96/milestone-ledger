package dev.sakshi.milestoneledger.certification;

import java.util.List;
import java.util.UUID;

import dev.sakshi.milestoneledger.payments.PaymentQueryService;
import dev.sakshi.milestoneledger.payments.PaymentResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demands")
public class DemandController {
    private final CertificationService service;
    private final PaymentQueryService queries;

    public DemandController(CertificationService service, PaymentQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    @GetMapping("/{demandId}")
    public CertificationResponse.DemandResponse demand(@PathVariable UUID demandId) {
        return service.demand(demandId);
    }
    @GetMapping
    public PaymentResponses.Worklist<CertificationResponse.DemandResponse> demands(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return queries.demands(status, projectId, limit, cursor);
    }
}
