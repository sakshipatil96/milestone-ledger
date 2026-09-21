package dev.sakshi.milestoneledger.reconciliation;

import java.net.URI;
import java.util.UUID;

import dev.sakshi.milestoneledger.shared.web.CorrelationIdFilter;
import dev.sakshi.milestoneledger.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation-runs")
public class ReconciliationController {
    private final ReconciliationService service;
    private final ObjectMapper json;
    public ReconciliationController(ReconciliationService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<ReconciliationService.Accepted> create(@RequestBody byte[] body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        UUID projectId;
        try {
            if (body.length > 4096) throw new IllegalArgumentException();
            var node = json.readTree(body);
            if (node == null || !node.isObject() || node.size() != 1 || node.get("projectId") == null
                    || !node.get("projectId").isTextual()) throw new IllegalArgumentException();
            projectId = UUID.fromString(node.get("projectId").textValue());
        } catch (Exception failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
        }
        var accepted = service.create(projectId, key, CorrelationIdFilter.requestId(servletRequest));
        return ResponseEntity.accepted().location(URI.create("/api/v1/reconciliation-runs/" + accepted.runId()))
                .body(accepted);
    }

    @GetMapping("/{runId}")
    public ReconciliationService.Detail get(@PathVariable UUID runId) { return service.get(runId); }
}
