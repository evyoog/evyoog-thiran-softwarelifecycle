package com.vyoog.api.web;

import com.vyoog.identity.UserProvisioningService;
import com.vyoog.trace.CoverageMatrixService;
import com.vyoog.trace.TraceHop;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import com.vyoog.trace.TraceGraphAssemblyService;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceReachability;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0140/0141/0144/0145/0147: typed trace links, bounded traversal, and the requirement×test coverage matrix. */
@RestController
@RequestMapping("/api/v1/trace")
public class TraceLinkController {

    private final TraceGraphService trace;
    private final UserProvisioningService provisioning;
    private final CoverageMatrixService coverageMatrix;
    private final TraceGraphAssemblyService graphAssembly;

    public TraceLinkController(TraceGraphService trace, UserProvisioningService provisioning,
                                CoverageMatrixService coverageMatrix, TraceGraphAssemblyService graphAssembly) {
        this.trace = trace;
        this.provisioning = provisioning;
        this.coverageMatrix = coverageMatrix;
        this.graphAssembly = graphAssembly;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record CreateLink(
        @NotNull TraceObjectType fromType, @NotBlank String fromId,
        @NotNull TraceObjectType toType, @NotBlank String toId,
        @NotNull TraceLinkType linkType) {}

    public record LinkView(String id, String fromType, String fromId, String toType, String toId,
                            String linkType, Integer reviewedAtRevision) {}

    public record HopView(String type, String id) {}
    public record ReachabilityView(String type, String id, int depth, List<HopView> path) {}

    private static LinkView toView(TraceLink l) {
        return new LinkView(
            l.getId().toString(), l.getFromType().name(), l.getFromId().toString(),
            l.getToType().name(), l.getToId().toString(), l.getLinkType().name(),
            l.getReviewedAtRevision());
    }

    private static ReachabilityView toView(TraceReachability r) {
        return new ReachabilityView(r.type().name(), r.id().toString(), r.depth(),
            r.path().stream().map(TraceLinkController::toView).toList());
    }

    private static HopView toView(TraceHop h) {
        return new HopView(h.type().name(), h.id().toString());
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkView createLink(@RequestBody CreateLink body, @AuthenticationPrincipal Jwt jwt) {
        TraceLink link = trace.createLink(
            body.fromType(), UUID.fromString(body.fromId()),
            body.toType(), UUID.fromString(body.toId()), body.linkType(),
            currentUserId(jwt));
        return toView(link);
    }

    @DeleteMapping("/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLink(@PathVariable UUID id) {
        trace.deleteLink(id);
    }

    @PostMapping("/links/{id}/review")
    public LinkView reviewLink(@PathVariable UUID id) {
        return toView(trace.reviewLink(id));
    }

    public record DirectLinksView(List<LinkView> outgoing, List<LinkView> incoming) {}

    /** The direct links touching this object — for showing/managing them, not traversal. */
    @GetMapping("/{type}/{id}/links")
    public DirectLinksView links(@PathVariable TraceObjectType type, @PathVariable UUID id) {
        var direct = trace.linksFor(type, id);
        return new DirectLinksView(
            direct.outgoing().stream().map(TraceLinkController::toView).toList(),
            direct.incoming().stream().map(TraceLinkController::toView).toList());
    }

    @GetMapping("/{type}/{id}/downstream")
    public List<ReachabilityView> downstream(@PathVariable TraceObjectType type, @PathVariable UUID id,
                                              @RequestParam(defaultValue = "12") int depth) {
        return trace.downstream(type, id, depth).stream().map(TraceLinkController::toView).toList();
    }

    @GetMapping("/{type}/{id}/upstream")
    public List<ReachabilityView> upstream(@PathVariable TraceObjectType type, @PathVariable UUID id,
                                            @RequestParam(defaultValue = "12") int depth) {
        return trace.upstream(type, id, depth).stream().map(TraceLinkController::toView).toList();
    }

    public record GraphNodeView(String type, String id, String label, boolean root) {}
    public record GraphEdgeView(String fromType, String fromId, String toType, String toId, String linkType) {}
    public record GraphView(List<GraphNodeView> nodes, List<GraphEdgeView> edges) {}

    /**
     * VYB-0215: the whole need→requirement→design→code→test graph around one node, not
     * just its direct links or one path at a time — see {@link TraceGraphAssemblyService}
     * for why this needed a new assembly step on top of {@code upstream}/{@code downstream}.
     */
    @GetMapping("/{type}/{id}/graph")
    public GraphView graph(@PathVariable TraceObjectType type, @PathVariable UUID id,
                           @RequestParam(defaultValue = "6") int depth) {
        var g = graphAssembly.graphFor(type, id, depth);
        return new GraphView(
            g.nodes().stream().map(n -> new GraphNodeView(n.type(), n.id(), n.label(), n.root())).toList(),
            g.edges().stream().map(e -> new GraphEdgeView(e.fromType(), e.fromId(), e.toType(), e.toId(), e.linkType())).toList());
    }

    public record CoverageCellView(String requirementId, String requirementKey, String testCaseId, String testCaseKey, String state) {}
    public record TestTotalView(String testCaseId, String testCaseKey, long verified, long linkedNotRun, long suspect) {}
    public record CoverageMatrixView(List<CoverageCellView> cells, List<TestTotalView> testTotals) {}

    /** VYB-0147: requirement×test coverage for one application, four cell states — sparse over links that exist. */
    @GetMapping("/coverage-matrix/{applicationId}")
    public CoverageMatrixView coverageMatrix(@PathVariable UUID applicationId) {
        var matrix = coverageMatrix.forApplication(applicationId);
        return new CoverageMatrixView(
            matrix.cells().stream().map(c -> new CoverageCellView(
                c.requirementId().toString(), c.requirementKey(), c.testCaseId().toString(), c.testCaseKey(), c.state().name())).toList(),
            matrix.testTotals().stream().map(t -> new TestTotalView(
                t.testCaseId().toString(), t.testCaseKey(), t.verified(), t.linkedNotRun(), t.suspect())).toList());
    }
}
