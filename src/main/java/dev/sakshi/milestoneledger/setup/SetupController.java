package dev.sakshi.milestoneledger.setup;

import java.util.UUID;

import dev.sakshi.milestoneledger.setup.SetupResponses.CollectionResponse;
import dev.sakshi.milestoneledger.setup.SetupResponses.MilestoneResponse;
import dev.sakshi.milestoneledger.setup.SetupResponses.ProjectResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class SetupController {
    private final SetupQueryService service;

    public SetupController(SetupQueryService service) {
        this.service = service;
    }

    @GetMapping
    public CollectionResponse<ProjectResponse> projects(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return service.projects(limit, cursor);
    }

    @GetMapping("/{projectId}/milestones")
    public CollectionResponse<MilestoneResponse> milestones(
            @PathVariable UUID projectId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.milestones(projectId, limit, cursor);
    }
}
