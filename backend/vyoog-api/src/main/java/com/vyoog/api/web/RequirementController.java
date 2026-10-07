package com.vyoog.api.web;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.RequirementRewriteAdvisor;
import com.vyoog.changerequest.ChangeRequestService;
import com.vyoog.evidence.TestCaseSuggestionService;
import com.vyoog.proposal.AiProposalService;
import com.vyoog.proposal.ProposalKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.api.config.AccessScopeResolver;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.platform.IdempotencyService;
import com.vyoog.requirements.AcceptanceCriterion;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.GapPreviewService;
import com.vyoog.requirements.LifecycleHistoryService;
import com.vyoog.requirements.QualityScoreService;
import com.vyoog.requirements.Placement;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.requirements.RequirementSimilarityService;
import com.vyoog.requirements.RequirementSpecifications;
import com.vyoog.requirements.RequirementStatus;
import com.vyoog.trace.CoverageProjection;
import com.vyoog.trace.TraceGraphService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0130/0133: list with filter/sort/page, and the transition endpoint. The grid's
 * full server-side contract (VYB-0131 — multi-column sort, grouping) is frontend work
 * for a later session; this is the API surface it will call.
 */
@RestController
@RequestMapping("/api/v1/requirements")
public class RequirementController {

    private final RequirementRepository requirements;
    private final RequirementService service;
    private final AcceptanceCriterionService acceptanceCriteria;
    private final UserProvisioningService provisioning;
    private final TraceGraphService traceGraph;
    private final RequirementSimilarityService similarity;
    private final IdempotencyService idempotency;
    private final ChangeRequestService changeRequests;
    private final LifecycleHistoryService lifecycleHistory;
    private final QualityScoreService qualityScore;
    private final GapPreviewService gapPreview;
    private final RequirementRewriteAdvisor rewriteAdvisor;
    private final TestCaseSuggestionService testCaseSuggestions;
    private final PrincipalGuard guard;
    private final AiProposalService proposals;
    private final ObjectMapper json;

    /** VYB-0902: the matrix's "Edit req" column (Business Analyst, Architect); an administrator is never blocked. */
    private static final List<AccessRole> EDIT_ROLES = List.of(AccessRole.BUSINESS_ANALYST, AccessRole.ARCHITECT);

    public RequirementController(RequirementRepository requirements, RequirementService service,
                                  AcceptanceCriterionService acceptanceCriteria,
                                  UserProvisioningService provisioning, TraceGraphService traceGraph,
                                  RequirementSimilarityService similarity, IdempotencyService idempotency,
                                  ChangeRequestService changeRequests, LifecycleHistoryService lifecycleHistory,
                                  QualityScoreService qualityScore, GapPreviewService gapPreview,
                                  RequirementRewriteAdvisor rewriteAdvisor, TestCaseSuggestionService testCaseSuggestions,
                                  PrincipalGuard guard, AiProposalService proposals, ObjectMapper json) {
        this.requirements = requirements;
        this.service = service;
        this.acceptanceCriteria = acceptanceCriteria;
        this.provisioning = provisioning;
        this.traceGraph = traceGraph;
        this.similarity = similarity;
        this.idempotency = idempotency;
        this.changeRequests = changeRequests;
        this.lifecycleHistory = lifecycleHistory;
        this.qualityScore = qualityScore;
        this.gapPreview = gapPreview;
        this.rewriteAdvisor = rewriteAdvisor;
        this.testCaseSuggestions = testCaseSuggestions;
        this.guard = guard;
        this.proposals = proposals;
        this.json = json;
    }

    public record RequirementView(
        String id, String key, String capabilityId, String type, String status, String priority,
        String title, String statement, String rationale, int revision, Short qualityScore,
        // The grid defaults to newest-first, so the date it sorts by has to be visible —
        // an order the reader cannot check reads as an arbitrary one.
        String createdAt,
        boolean hasUpstream, boolean hasDesign, boolean hasCode, boolean hasTest,
        /**
         * VYB-0666: how many acceptance criteria this requirement has. On the list payload
         * because the grid has to be able to tell a requirement nobody can test yet from
         * one that is ready — without a second request per row.
         */
        int criteriaCount,
        // VYB-0813 (D17): the most recent transition's metadata — set automatically by
        // Requirement#transitionTo. The durable, append-only history of every
        // transition is audit_event, not these; these are "what/when/why was the
        // latest one" without a second request.
        String previousStatus, int revisionCount, String reason, String changedBy, String changedAt,
        int version) {}

    /** D12: name at most one of productId/applicationId/capabilityId; none means unplaced. */
    public record CreateRequirement(
        @NotBlank String title, @NotBlank String statement,
        String type, String priority, String capabilityId,
        String productId, String applicationId) {}

    public record UpdateRequirement(
        int revision, @NotBlank String title, @NotBlank String statement,
        String type, String priority, String capabilityId) {}

    public record TransitionRequest(@NotBlank String target, String reason) {}

    private static UUID uuidOrNull(String s) {
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    public record CriterionView(String id, short ordinal, String text) {}
    public record AddCriterion(@NotBlank String text) {}
    public record ReorderCriteria(@NotEmpty List<String> orderedIds) {}

    private static RequirementView toView(Requirement r, CoverageProjection cov) {
        return toView(r, cov, 0);
    }

    private static RequirementView toView(Requirement r, CoverageProjection cov, int criteriaCount) {
        return new RequirementView(
            r.getId().toString(), r.getKey(),
            r.getCapabilityId() == null ? null : r.getCapabilityId().toString(),
            r.getType(), r.getStatus().name(), r.getPriority(),
            r.getTitle(), r.getStatement(), r.getRationale(), r.getRevision(), r.getQualityScore(),
            r.getCreatedAt() == null ? null : r.getCreatedAt().toString(),
            cov != null && cov.hasUpstream(), cov != null && cov.hasDesign(),
            cov != null && cov.hasCode(), cov != null && cov.hasTest(), criteriaCount,
            r.getPreviousStatus(), r.getRevisionCount(), r.getReason(),
            r.getChangedBy() == null ? null : r.getChangedBy().toString(),
            r.getChangedAt() == null ? null : r.getChangedAt().toString(),
            r.getVersion());
    }

    private static CriterionView toView(AcceptanceCriterion c) {
        return new CriterionView(c.getId().toString(), c.getOrdinal(), c.getText());
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(),
            jwt.getClaimAsString("email"),
            jwt.getClaimAsString("preferred_username")).getId();
    }

    public record SimilarMatch(String id, String key, String title, String statement, double score) {}

    /** VYB-0135: not persisted, purely a read. */
    @GetMapping("/similar")
    public List<SimilarMatch> similar(@RequestParam String statement) {
        return similarity.findSimilar(statement, 10).stream()
            .map(m -> new SimilarMatch(m.id().toString(), m.key(), m.title(), m.statement(), m.score()))
            .toList();
    }

    @GetMapping
    public Page<RequirementView> list(
            @RequestParam(required = false) RequirementStatus status,
            @RequestParam(required = false) UUID capabilityId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) String title,
            /** VYB-0666: null means "both". The grid asks for true; the gap list asks for false. */
            @RequestParam(required = false) Boolean hasCriteria,
            /** VYB-0831: null means "both". Delivery's brief scope asks for true, to count how many approved requirements are actually ready to brief. */
            @RequestParam(required = false) Boolean hasTestCase,
            @PageableDefault(size = 50) Pageable pageable) {
        // VYB-0130 AC1: filters compose with AND — only the ones actually supplied.
        Specification<Requirement> spec = RequirementSpecifications.notDeleted();
        if (status != null) spec = spec.and(RequirementSpecifications.hasStatus(status));
        if (capabilityId != null) spec = spec.and(RequirementSpecifications.hasCapability(capabilityId));
        if (type != null) spec = spec.and(RequirementSpecifications.hasType(type));
        if (priority != null) spec = spec.and(RequirementSpecifications.hasPriority(priority));
        if (ownerId != null) spec = spec.and(RequirementSpecifications.hasOwner(ownerId));
        if (title != null) spec = spec.and(RequirementSpecifications.titleContains(title));
        if (hasCriteria != null) spec = spec.and(RequirementSpecifications.hasAcceptanceCriteria(hasCriteria));
        if (hasTestCase != null) spec = spec.and(RequirementSpecifications.hasTestCase(hasTestCase));

        Page<Requirement> found = requirements.findAll(spec, pageable);
        List<UUID> ids = found.map(Requirement::getId).toList();
        Map<UUID, CoverageProjection> coverage = traceGraph.coverageFor(ids);
        // One count query for the page, not one per row.
        Map<UUID, Integer> criteriaCounts = acceptanceCriteria.countsFor(ids);
        return found.map(r -> toView(r, coverage.get(r.getId()), criteriaCounts.getOrDefault(r.getId(), 0)));
    }

    @GetMapping("/{id}")
    public RequirementView get(@PathVariable UUID id) {
        Requirement r = requirements.findById(id).orElseThrow(NoSuchElementException::new);
        return toView(r, traceGraph.coverageFor(List.of(id)).get(id),
            acceptanceCriteria.countsFor(List.of(id)).getOrDefault(id, 0));
    }

    /**
     * VYB-0902 (F02): PATCH and DELETE used to need only a valid login. The role is checked at
     * the requirement's own scope (capability, else app, else product, else platform), so a
     * grant on the product above it counts.
     */
    private void requireEditRole(Jwt jwt, UUID requirementId, String action) {
        Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        ScopeType type;
        UUID scopeId;
        if (r.getCapabilityId() != null) { type = ScopeType.CAPABILITY; scopeId = r.getCapabilityId(); }
        else if (r.getApplicationId() != null) { type = ScopeType.APP; scopeId = r.getApplicationId(); }
        else if (r.getProductId() != null) { type = ScopeType.PRODUCT; scopeId = r.getProductId(); }
        else { type = ScopeType.PLATFORM; scopeId = null; }
        guard.requireAnyRoleOrAdmin(jwt, EDIT_ROLES, type, scopeId, action);
    }

    private static final String CREATE_ENDPOINT = "POST /requirements";

    // VYB-0906: the placement is in the body, so the scoped check is in the handler.
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequirementView create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   @RequestBody CreateRequirement body, @AuthenticationPrincipal Jwt jwt) {
        // VYB-0906: the interceptor already required the role somewhere; with a placement given, it must
        // be held at (or above) that placement. An unplaced requirement belongs to no scope, so the
        // "somewhere" gate is all there is to check.
        if (body.capabilityId() != null || body.applicationId() != null || body.productId() != null) {
            var scope = AccessScopeResolver.ofPlacement(
                uuidOrNull(body.capabilityId()), uuidOrNull(body.applicationId()), uuidOrNull(body.productId()));
            guard.requireAnyRoleOrAdmin(jwt, AccessRule.CREATE_EDIT_REQ.roles(), scope.type(), scope.id(),
                "create a requirement here");
        }
        // VYB-0132 AC1/AC2: a repeat with the same key returns the first response,
        // not a second row.
        if (idempotencyKey != null) {
            var existing = idempotency.find(idempotencyKey, CREATE_ENDPOINT);
            if (existing.isPresent()) {
                return toView(requirements.findById(existing.get()).orElseThrow(), null);
            }
        }

        Requirement r = service.create(
            body.title(), body.statement(), body.type(), body.priority(),
            Placement.of(uuidOrNull(body.productId()), uuidOrNull(body.applicationId()),
                uuidOrNull(body.capabilityId())),
            currentUserId(jwt));

        if (idempotencyKey != null) {
            if (!idempotency.record(idempotencyKey, CREATE_ENDPOINT, r.getId())) {
                // Lost a race with a concurrent identical request — its row is the
                // real "first" response, not this one.
                UUID winnerId = idempotency.find(idempotencyKey, CREATE_ENDPOINT).orElseThrow();
                return toView(requirements.findById(winnerId).orElseThrow(), null);
            }
        }
        return toView(r, null); // brand new — no trace links can exist against it yet
    }

    /**
     * VYB-0390 AC1: editing an APPROVED requirement without a
     * {@code changeRequestId} is refused by {@code RequirementService#update} itself
     * — {@code changeRequestId} is the one way through, and only once that change
     * request is actually APPROVED and actually names this requirement (checked by
     * {@code ChangeRequestService#assertCanEdit} before the edit is allowed to run).
     */
    @PatchMapping("/{id}")
    public RequirementView update(@PathVariable UUID id, @RequestBody UpdateRequirement body,
                                   @RequestParam(required = false) UUID changeRequestId,
                                   @AuthenticationPrincipal Jwt jwt) {
        requireEditRole(jwt, id, "edit requirement");
        UUID capabilityId = body.capabilityId() == null ? null : UUID.fromString(body.capabilityId());
        Requirement r;
        if (changeRequestId != null) {
            changeRequests.assertCanEdit(changeRequestId, id);
            r = service.applyChangeRequestEdit(
                id, body.revision(), body.title(), body.statement(), body.type(), body.priority(),
                capabilityId, currentUserId(jwt));
        } else {
            r = service.update(
                id, body.revision(), body.title(), body.statement(), body.type(), body.priority(),
                capabilityId, currentUserId(jwt));
        }
        return toView(r, traceGraph.coverageFor(List.of(r.getId())).get(r.getId()));
    }

    /**
     * VYB-0666: removes a requirement from the register.
     *
     * <p>Soft — the row and everything referencing it survive, and every read path already
     * hides deleted rows. 204 rather than a body: there is nothing left to return, and a
     * second call on the same id is the same answer rather than a 404.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String reason,
                        @AuthenticationPrincipal Jwt jwt) {
        requireEditRole(jwt, id, "delete a requirement");
        service.delete(id, reason, currentUserId(jwt));
    }

    // VYB-0906: the per-edge role and separation-of-duties checks are RequirementTransitionAuthorizer, in the service.
    @RequiresAccess(AccessRule.PERSON)
    @PostMapping("/{id}/transition")
    public RequirementView transition(@PathVariable UUID id, @RequestParam int revision,
                                       @RequestBody TransitionRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        Requirement r = service.transition(
            id, revision, RequirementStatus.valueOf(body.target()), body.reason(), currentUserId(jwt));
        return toView(r, traceGraph.coverageFor(List.of(r.getId())).get(r.getId()));
    }

    @GetMapping("/{id}/acceptance-criteria")
    public List<CriterionView> listCriteria(@PathVariable UUID id) {
        return acceptanceCriteria.list(id).stream().map(RequirementController::toView).toList();
    }

    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.REQUIREMENT)
    @PostMapping("/{id}/acceptance-criteria")
    @ResponseStatus(HttpStatus.CREATED)
    public CriterionView addCriterion(@PathVariable UUID id, @RequestBody AddCriterion body) {
        return toView(acceptanceCriteria.add(id, body.text()));
    }

    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.REQUIREMENT)
    @PutMapping("/{id}/acceptance-criteria/order")
    public List<CriterionView> reorderCriteria(@PathVariable UUID id, @RequestBody ReorderCriteria body) {
        List<UUID> ids = body.orderedIds().stream().map(UUID::fromString).toList();
        return acceptanceCriteria.reorder(id, ids).stream().map(RequirementController::toView).toList();
    }

    /** VYB-0191: editing existing criterion text in place, alongside add/reorder/remove. */
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.CRITERION, idVar = "criterionId")
    @PatchMapping("/acceptance-criteria/{criterionId}")
    public CriterionView editCriterion(@PathVariable UUID criterionId, @RequestBody AddCriterion body) {
        return toView(acceptanceCriteria.edit(criterionId, body.text()));
    }

    public record LifecycleStageView(String stage, String actorId, String occurredAt) {}

    /** VYB-0119/0193: who acted, when, at each of the six stages — absent means it hasn't happened. */
    @GetMapping("/{id}/lifecycle")
    public List<LifecycleStageView> lifecycle(@PathVariable UUID id) {
        return lifecycleHistory.historyFor(id).stream()
            .map(s -> new LifecycleStageView(s.stage(), s.actorId() == null ? null : s.actorId().toString(),
                s.occurredAt().toString()))
            .toList();
    }

    public record LifecycleSpineView(String stage, long count) {}

    /**
     * VYB-0222: the lifecycle coverage spine — how many requirements have reached each
     * stage, across the whole register at once. A literal path segment
     * ("lifecycle-spine"), not nested under {@code /{id}}, so it's unambiguous which
     * this is — Spring's path matching would resolve it correctly either way, but a
     * human reading the route shouldn't have to know that.
     */
    @GetMapping("/lifecycle-spine")
    public List<LifecycleSpineView> lifecycleSpine() {
        return lifecycleHistory.spine().stream()
            .map(s -> new LifecycleSpineView(s.stage(), s.count()))
            .toList();
    }

    public record AuthoringSignalsRequest(
        String statement, int criteriaCount, boolean hasCapability, boolean hasUpstream) {}
    public record GapPreviewView(String ruleKey, String reason) {}
    public record AuthoringSignalsView(
        int qualityScore, Map<String, Integer> qualityBreakdown,
        List<GapPreviewView> avoidableGaps, List<GapPreviewView> expectedGaps) {}

    /**
     * VYB-0202/0203: live, save-nothing preview of the quality score and which gap
     * classes would fire — called on every debounced keystroke while authoring, the
     * same pattern {@code /lint} and {@code /requirements/similar} already use.
     */
    // VYB-0906: computes advice, stores nothing.
    @RequiresAccess(AccessRule.PERSON)
    @PostMapping("/authoring-signals")
    public AuthoringSignalsView authoringSignals(@RequestBody AuthoringSignalsRequest body) {
        var score = qualityScore.score(body.statement(), body.criteriaCount(), body.hasUpstream());
        var gaps = gapPreview.preview(body.statement(), body.criteriaCount(), body.hasCapability(), body.hasUpstream());
        return new AuthoringSignalsView(score.total(), score.breakdown(),
            gaps.avoidable().stream().map(g -> new GapPreviewView(g.ruleKey(), g.reason())).toList(),
            gaps.expected().stream().map(g -> new GapPreviewView(g.ruleKey(), g.reason())).toList());
    }

    /** @param requirementId optional: the requirement the statement belongs to, so accepting the proposal can edit it; absent while a requirement is still being drafted */
    public record RewriteSuggestionRequest(String statement, int criteriaCount, boolean hasUpstream, UUID requirementId) {}
    /** @param proposalId VYB-0938: decide it at {@code POST /ai-proposals/{id}/decision}; nothing has been applied */
    public record RewriteSuggestionView(String rewrittenStatement, List<String> changes, String model, String proposalId) {}

    /**
     * VYB-0794 (Part 2): a real AI-generated rewrite, not baked into {@link
     * #authoringSignals}'s per-keystroke flow — this is its own explicit action (a
     * "Suggest rewrite" button, not a debounced call), reusing {@code
     * QualityScoreService}'s existing breakdown for exactly this statement so the
     * model is told precisely what already scored badly rather than re-deriving it.
     * Save-nothing, same as {@code authoringSignals} — a rewrite is a suggestion
     * shown to a human, never a requirement mutated on its own.
     */
    // VYB-0906: an AI proposal; a person applies it.
    @RequiresAccess(AccessRule.PERSON)
    @PostMapping("/rewrite-suggestion")
    public RewriteSuggestionView rewriteSuggestion(@RequestBody RewriteSuggestionRequest body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt); // a proposal is attributed to a person
        var score = qualityScore.score(body.statement(), body.criteriaCount(), body.hasUpstream());
        var suggestion = rewriteAdvisor.suggest(body.statement(), score.breakdown());
        // VYB-0938: recorded, applied by nobody until a person decides it.
        var payload = json.createObjectNode().put("statement", suggestion.rewrittenStatement());
        suggestion.changes().forEach(payload.putArray("changes")::add);
        UUID proposalId = proposals.record(ProposalKind.REWRITE, body.requirementId(), payload, rewriteAdvisor.modelName(), currentUserId(jwt));
        return new RewriteSuggestionView(suggestion.rewrittenStatement(), suggestion.changes(), rewriteAdvisor.modelName(), proposalId.toString());
    }

    /** @param proposalId VYB-0938: accept it (optionally edited) or reject it at {@code POST /ai-proposals/{id}/decision}; no test case exists until then */
    public record TestCaseSuggestionView(String category, String title, String description, String rationale, String proposalId) {}
    public record RelatedRequirementView(String key, String title, String direction) {}
    public record TestCaseSuggestionsView(
        List<TestCaseSuggestionView> suggestions, List<RelatedRequirementView> relatedRequirements, String model) {}

    /**
     * VYB-0824: dependency-aware test-case proposals, same save-nothing/explicit-action
     * discipline as {@link #rewriteSuggestion} — a suggestion becomes a real test case
     * only via {@code POST /api/v1/test-cases} ({@code TestCaseService.draft}), never
     * here. {@code relatedRequirements} is returned so the caller can show exactly which
     * trace-linked requirements the DEPENDENCY suggestions are grounded in, not just
     * that some exist.
     */
    // VYB-0906: an AI proposal; nothing is stored.
    @RequiresAccess(AccessRule.PERSON)
    @PostMapping("/{id}/test-case-suggestions")
    public TestCaseSuggestionsView testCaseSuggestions(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        var result = testCaseSuggestions.suggest(id);
        UUID actor = currentUserId(jwt);
        return new TestCaseSuggestionsView(
            result.suggestions().stream()
                .map(s -> suggestionView(id, s, result.model(), actor))
                .toList(),
            result.relatedRequirements().stream()
                .map(r -> new RelatedRequirementView(r.key(), r.title(), r.direction()))
                .toList(),
            result.model());
    }

    /** VYB-0938: each suggestion is recorded as a proposal the moment it is made, so its decision is on record either way. */
    private TestCaseSuggestionView suggestionView(UUID requirementId, com.vyoog.ai.TestCaseGenerator.Suggestion s, String model, UUID actor) {
        var payload = json.createObjectNode()
            .put("category", s.category().name()).put("title", s.title())
            .put("description", s.description() == null ? "" : s.description())
            .put("rationale", s.rationale() == null ? "" : s.rationale());
        UUID proposalId = proposals.record(ProposalKind.TEST_CASE, requirementId, payload, model, actor);
        return new TestCaseSuggestionView(s.category().name(), s.title(), s.description(), s.rationale(), proposalId.toString());
    }

    public record BulkTestCaseSuggestionsRequest(@NotEmpty List<UUID> requirementIds) {}
    public record RequirementSuggestionsView(
        String requirementId, String requirementKey, String requirementTitle, boolean pulledInAsDependency,
        List<TestCaseSuggestionView> suggestions, List<RelatedRequirementView> relatedRequirements) {}
    public record BulkTestCaseSuggestionsView(List<RequirementSuggestionsView> perRequirement, String model) {}

    /**
     * VYB-0826: one call per selected requirement AND every requirement directly
     * trace-linked to one of them — {@code pulledInAsDependency} tells the caller which
     * is which, so a review screen can label a card "because it depends on VY-x" rather
     * than implying it was picked directly. Same save-nothing discipline as the
     * single-requirement endpoint above: nothing here ever creates a test case.
     */
    // VYB-0906: an AI proposal; nothing is stored.
    @RequiresAccess(AccessRule.PERSON)
    @PostMapping("/test-case-suggestions/bulk")
    public BulkTestCaseSuggestionsView testCaseSuggestionsBulk(@RequestBody BulkTestCaseSuggestionsRequest body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        var result = testCaseSuggestions.suggestBulk(body.requirementIds());
        UUID actor = currentUserId(jwt);
        return new BulkTestCaseSuggestionsView(
            result.perRequirement().stream()
                .map(rs -> new RequirementSuggestionsView(
                    rs.requirementId().toString(), rs.requirementKey(), rs.requirementTitle(), rs.pulledInAsDependency(),
                    rs.suggestions().stream()
                        .map(s -> suggestionView(rs.requirementId(), s, result.model(), actor))
                        .toList(),
                    rs.relatedRequirements().stream()
                        .map(r -> new RelatedRequirementView(r.key(), r.title(), r.direction()))
                        .toList()))
                .toList(),
            result.model());
    }

    public record DependencyClusterRequest(@NotEmpty List<UUID> requirementIds) {}
    public record ClusterMemberView(String requirementId, String key, String title, boolean selected) {}
    public record ClusterEdgeView(String fromKey, String toKey, String linkType) {}
    public record DependencyClusterView(List<ClusterMemberView> members, List<ClusterEdgeView> edges, boolean capped) {}

    /**
     * VYB-0830: the cheap preview a generation flow shows before anyone commits to
     * actually generating anything — the full connected dependency component for the
     * given selection, no AI call. {@code /test-case-suggestions/bulk} above generates
     * for exactly this same set; calling this first is how a caller sees it before
     * committing.
     */
    // VYB-0906: read-only analysis.
    @RequiresAccess(AccessRule.PERSON)
    @PostMapping("/dependency-cluster")
    public DependencyClusterView dependencyCluster(@RequestBody DependencyClusterRequest body) {
        var cluster = testCaseSuggestions.dependencyCluster(body.requirementIds());
        return new DependencyClusterView(
            cluster.members().stream()
                .map(m -> new ClusterMemberView(m.requirementId().toString(), m.key(), m.title(), m.selected()))
                .toList(),
            cluster.edges().stream()
                .map(e -> new ClusterEdgeView(e.fromKey(), e.toKey(), e.linkType()))
                .toList(),
            cluster.capped());
    }
}
