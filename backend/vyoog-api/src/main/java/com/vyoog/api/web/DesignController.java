package com.vyoog.api.web;

import com.vyoog.design.DesignEdge;
import com.vyoog.design.DesignFlow;
import com.vyoog.design.DesignNode;
import com.vyoog.design.DesignService;
import com.vyoog.design.NodeKind;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0490–0495. */
@RestController
@RequestMapping("/api/v1/design")
public class DesignController {

    private final DesignService service;
    private final UserProvisioningService provisioning;

    public DesignController(DesignService service, UserProvisioningService provisioning) {
        this.service = service;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record FlowView(String id, String applicationId) {}
    public record NodeView(String id, String flowId, String kind, String label, String note, List<String> requirementIds) {}
    public record EdgeView(String id, String flowId, String fromNode, String toNode, String label) {}
    public record CreateNode(@NotBlank String kind, @NotBlank String label, String note) {}
    public record CreateEdge(@NotBlank String fromNode, @NotBlank String toNode, String label) {}

    private FlowView toView(DesignFlow f) {
        return new FlowView(f.getId().toString(), f.getApplicationId().toString());
    }

    private NodeView toView(DesignNode n) {
        List<String> reqIds = service.requirementsFor(n.getId()).stream().map(UUID::toString).toList();
        return new NodeView(n.getId().toString(), n.getFlowId().toString(), n.getKind().name(), n.getLabel(), n.getNote(), reqIds);
    }

    private static EdgeView toView(DesignEdge e) {
        return new EdgeView(e.getId().toString(), e.getFlowId().toString(), e.getFromNode().toString(), e.getToNode().toString(), e.getLabel());
    }

    @PostMapping("/flows")
    @ResponseStatus(HttpStatus.CREATED)
    public FlowView createFlow(@RequestParam UUID applicationId, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.createFlow(applicationId, currentUserId(jwt)));
    }

    @GetMapping("/flows")
    public FlowView flowFor(@RequestParam UUID applicationId) {
        return toView(service.flowFor(applicationId));
    }

    @DeleteMapping("/flows/{id}")
    public void deleteFlow(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteFlow(id, currentUserId(jwt));
    }

    @GetMapping("/flows/{id}/nodes")
    public List<NodeView> nodes(@PathVariable UUID id) {
        return service.nodesIn(id).stream().map(this::toView).toList();
    }

    @GetMapping("/flows/{id}/edges")
    public List<EdgeView> edges(@PathVariable UUID id) {
        return service.edgesIn(id).stream().map(DesignController::toView).toList();
    }

    /**
     * VYB-0666: draws the flow from the application's own requirements. Additive and
     * repeatable — re-running after an import adds only what is new and never removes
     * anything already on the diagram, so a hand-edited flow survives a regenerate.
     */
    @PostMapping("/flows/{id}/generate")
    public DesignService.Generated generate(@PathVariable UUID id, @RequestParam UUID applicationId,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.generateFrom(id, applicationId, currentUserId(jwt));
    }

    @PostMapping("/flows/{id}/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeView addNode(@PathVariable UUID id, @RequestBody CreateNode body, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.addNode(id, NodeKind.valueOf(body.kind()), body.label(), body.note(), currentUserId(jwt)));
    }

    @PostMapping("/flows/{id}/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public EdgeView addEdge(@PathVariable UUID id, @RequestBody CreateEdge body, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.addEdge(id, UUID.fromString(body.fromNode()), UUID.fromString(body.toNode()),
            body.label(), currentUserId(jwt)));
    }

    @DeleteMapping("/nodes/{id}")
    public void deleteNode(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteNode(id, currentUserId(jwt));
    }

    @PutMapping("/nodes/{nodeId}/requirements/{requirementId}")
    public void link(@PathVariable UUID nodeId, @PathVariable UUID requirementId, @AuthenticationPrincipal Jwt jwt) {
        service.linkRequirement(nodeId, requirementId, currentUserId(jwt));
    }

    @DeleteMapping("/nodes/{nodeId}/requirements/{requirementId}")
    public void unlink(@PathVariable UUID nodeId, @PathVariable UUID requirementId, @AuthenticationPrincipal Jwt jwt) {
        service.unlinkRequirement(nodeId, requirementId, currentUserId(jwt));
    }

    public record UncoveredView(String id, String key, String title) {}
    public record OrphanView(String id, String label, String kind) {}

    /** VYB-0537: both counts the module tab badge shows. */
    @GetMapping("/applications/{applicationId}/coverage")
    public UncoveredView[] noDesign(@PathVariable UUID applicationId) {
        return service.requirementsWithNoDesign(applicationId).stream()
            .map(u -> new UncoveredView(u.id(), u.key(), u.title())).toArray(UncoveredView[]::new);
    }

    @GetMapping("/flows/{id}/orphan-nodes")
    public List<OrphanView> orphanNodes(@PathVariable UUID id) {
        return service.nodesWithNoRequirement(id).stream()
            .map(o -> new OrphanView(o.id(), o.label(), o.kind())).toList();
    }

    public record ProgressView(
        int totalRequirements, int verifiedRequirements, int verifiedPct,
        int deployedRequirements, int deployedPct) {}

    /**
     * VYB-0816: what the "Testing" and "Deployment" milestone nodes a generate run adds
     * actually mean — real evidence (verification and deployment records), not the
     * requirement's own status. Same server-side-percentage pattern as
     * {@link #coverageSummary}.
     */
    @GetMapping("/flows/{id}/progress")
    public ProgressView progress(@PathVariable UUID id) {
        var p = service.progressFor(id);
        int verifiedPct = p.totalRequirements() == 0 ? 0 : Math.round(100f * p.verifiedRequirements() / p.totalRequirements());
        int deployedPct = p.totalRequirements() == 0 ? 0 : Math.round(100f * p.deployedRequirements() / p.totalRequirements());
        return new ProgressView(
            p.totalRequirements(), p.verifiedRequirements(), verifiedPct, p.deployedRequirements(), deployedPct);
    }

    public record CoverageSummaryView(
        int totalRequirements, int requirementsWithDesign, int requirementCoveragePct,
        int totalNodes, int nodesWithRequirement, int nodeCoveragePct) {}

    /**
     * VYB-0537 (session 16): the actual badge — two real percentages, not just the
     * two offending-item lists {@link #noDesign}/{@link #orphanNodes} already
     * returned. Division happens here, once, server-side, not duplicated in the
     * frontend against whatever total it can separately scrape together.
     */
    @GetMapping("/applications/{applicationId}/flows/{flowId}/coverage-summary")
    public CoverageSummaryView coverageSummary(@PathVariable UUID applicationId, @PathVariable UUID flowId) {
        var s = service.coverageSummary(applicationId, flowId);
        int reqPct = s.totalRequirements() == 0 ? 100 : Math.round(100f * s.requirementsWithDesign() / s.totalRequirements());
        int nodePct = s.totalNodes() == 0 ? 100 : Math.round(100f * s.nodesWithRequirement() / s.totalNodes());
        return new CoverageSummaryView(
            s.totalRequirements(), s.requirementsWithDesign(), reqPct,
            s.totalNodes(), s.nodesWithRequirement(), nodePct);
    }
}
