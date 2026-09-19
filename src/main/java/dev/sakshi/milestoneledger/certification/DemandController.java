package dev.sakshi.milestoneledger.certification;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demands")
public class DemandController {
    private final CertificationService service;

    public DemandController(CertificationService service) {
        this.service = service;
    }

    @GetMapping("/{demandId}")
    public CertificationResponse.DemandResponse demand(@PathVariable UUID demandId) {
        return service.demand(demandId);
    }
}
