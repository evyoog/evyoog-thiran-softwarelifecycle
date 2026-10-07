package com.vyoog.brief;

import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserRepository;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.proposal.AiProposalService;
import com.vyoog.requirements.AcceptanceCriterion;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.PlacementLevel;
import com.vyoog.requirements.RequirementScopeService;
import com.vyoog.requirements.RequirementStatus;
import com.vyoog.trace.CoverageProjection;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.evidence.TestCaseQueryService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** VYB-0450–0457: gathers scope, requires a developer, and hands off to {@link BriefContentGenerator}. */
@Service
public class BriefService {

    private final BriefRepository briefs;
    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;
    private final CapabilityRepository capabilities;
    private final AppUserRepository users;
    private final TraceGraphService traceGraph;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    /** D12: the one place that knows what "in scope" means now that levels exist. */
    private final RequirementScopeService scopes;
    /** VYB-0938: the only source of AI text in a brief is what a person accepted. */
    private final AiProposalService proposals;
    /** VYB-0831: the gate — only a requirement with at least one test case is ever briefed. */
    private final TestCaseQueryService testCases;

    public BriefService(BriefRepository briefs, RequirementRepository requirements,
                         AcceptanceCriterionRepository criteria, CapabilityRepository capabilities,
                         AppUserRepository users, TraceGraphService traceGraph, JdbcTemplate jdbc,
                         AuditService audit, RequirementScopeService scopes,
                         AiProposalService proposals, TestCaseQueryService testCases) {
        this.briefs = briefs;
        this.requirements = requirements;
        this.criteria = criteria;
        this.capabilities = capabilities;
        this.users = users;
        this.traceGraph = traceGraph;
        this.jdbc = jdbc;
        this.audit = audit;
        this.scopes = scopes;
        this.proposals = proposals;
        this.testCases = testCases;
    }

    /** VYB-0452 AC1: refused without a developer, before anything else is computed. */
    @Transactional
    public Brief generate(UUID applicationId, String applicationName, List<UUID> capabilityIds,
                           BriefTarget target, UUID developerId, UUID actor) {
        return generate(applicationId, applicationName, capabilityIds, target, developerId, actor, BriefSection.ALL);
    }

    /** @param sections which optional parts of the brief to include; null or empty means all. */
    @Transactional
    public Brief generate(UUID applicationId, String applicationName, List<UUID> capabilityIds,
                           BriefTarget target, UUID developerId, UUID actor, Set<BriefSection> sections) {
        return generate(applicationId, applicationName, capabilityIds, target, developerId, actor, sections, false);
    }

    /**
     * @param includeReviewedElaborations VYB-0938 (was VYB-0817's {@code includeAiElaboration}): add, for each requirement,
     *     the AI elaboration a person accepted for it at its current revision. Generating never calls the AI; the drafts are
     *     made and decided separately ({@link BriefElaborationDrafter}, then the review endpoint). An explicit opt-in, not
     *     folded into {@code sections}, so an existing caller that asked for nothing in particular gets none. False is the
     *     only backward-compatible default.
     */
    @Transactional
    public Brief generate(UUID applicationId, String applicationName, List<UUID> capabilityIds,
                           BriefTarget target, UUID developerId, UUID actor, Set<BriefSection> sections,
                           boolean includeReviewedElaborations) {
        if (developerId == null) {
            throw new IllegalArgumentException("A brief cannot be generated without a developer assigned");
        }
        AppUser developer = users.findById(developerId).orElseThrow(
            () -> new NoSuchElementException("No such developer"));

        Resolved resolved = resolve(applicationId, capabilityIds);
        List<UUID> scopeCapabilityIds = resolved.scopeCapabilityIds();
        List<String> capabilityNames = scopeCapabilityIds.isEmpty() ? List.of()
            : capabilities.findAllById(scopeCapabilityIds).stream().map(Capability::getName).toList();
        Map<UUID, PlacementLevel> levels = resolved.levels();
        List<Requirement> inScope = resolved.inScope();
        List<Requirement> approved = resolved.approved();
        Map<String, Long> excludedByStatus = resolved.excludedByStatus();
        Map<UUID, List<TestCaseQueryService.RequirementTestCaseRow>> testCasesByRequirement = resolved.testCasesByRequirement();
        List<Requirement> scopeRequirements = resolved.scopeRequirements();
        long excludedNoTestCase = approved.size() - scopeRequirements.size();

        // A brief with no requirements is a header and a definition of done for nothing.
        // It used to generate happily and hand back a file that looked like a brief, which
        // is worse than a refusal: the reader has to notice the absence themselves. Refused
        // here as well as disabled in the UI, so the API cannot produce one either.
        if (scopeRequirements.isEmpty()) {
            String message;
            if (inScope.isEmpty()) {
                message = "Nothing in scope — these capabilities hold no requirements, so there is nothing to brief.";
            } else if (approved.isEmpty()) {
                message = "None of the %d requirements in scope are approved, so the brief would be empty. Approve them in Requirements first — only approved requirements are briefed."
                    .formatted(inScope.size());
            } else {
                message = "None of the %d approved requirements in scope have a test case yet, so the brief would be empty. Only requirements with at least one test case are briefed — generate or draft one first (Quality → Verification), then try again."
                    .formatted(approved.size());
            }
            throw new IllegalStateException(message);
        }

        Map<UUID, CoverageProjection> coverage =
            traceGraph.coverageFor(scopeRequirements.stream().map(Requirement::getId).toList());
        List<BriefRequirementView> views = scopeRequirements.stream()
            .map(r -> {
                List<String> criteriaText = criteria.findAllByRequirementIdOrderByOrdinalAsc(r.getId())
                    .stream().map(AcceptanceCriterion::getText).toList();
                boolean hasTest = coverage.containsKey(r.getId()) && coverage.get(r.getId()).hasTest();
                List<BriefTestCase> ownTestCases = testCasesByRequirement.getOrDefault(r.getId(), List.of()).stream()
                    .map(tc -> new BriefTestCase(tc.key(), tc.title(), tc.description(), tc.category()))
                    .toList();
                return new BriefRequirementView(
                    r.getKey(), r.getTitle(), r.getStatement(), r.getRationale(), r.getType(), r.getRevision(),
                    hasTest, criteriaText, r.getQualityScore(),
                    null,
                    levels.getOrDefault(r.getId(), PlacementLevel.CAPABILITY), null, ownTestCases);
            })
            .toList();

        int elaborated = 0;
        if (includeReviewedElaborations) {
            // VYB-0938: generation never calls the AI. A brief carries only what a person accepted for the requirement
            // as it is now; an elaboration written for earlier words is not offered again.
            Map<UUID, String> accepted = proposals.acceptedBriefElaborations(
                scopeRequirements.stream().map(Requirement::getId).toList());
            Map<String, String> byKey = new LinkedHashMap<>();
            for (Requirement r : scopeRequirements) {
                if (accepted.containsKey(r.getId())) byKey.put(r.getKey(), accepted.get(r.getId()));
            }
            elaborated = byKey.size();
            views = views.stream().map(v -> v.withAiElaboration(byKey.get(v.key()))).toList();
        }

        Instant asOf = Instant.now();
        String content = BriefContentGenerator.generate(
            applicationName, capabilityNames, views, target, developer.getDisplayName(), asOf, sections,
            excludedByStatus, excludedNoTestCase);

        // saveAndFlush, not save: the child rows below go in through JdbcTemplate, which
        // writes immediately, while Hibernate assigns this entity's UUID in memory and
        // defers its INSERT to flush at commit. Plain save() therefore left every
        // brief_requirement row pointing at a brief that did not exist yet, and the whole
        // transaction died on brief_requirement_brief_id_fkey. The bug hid for as long as
        // it did because a scope with no requirements never runs the loop at all — an
        // application with no capabilities generated a brief perfectly happily.
        Brief brief = briefs.saveAndFlush(new Brief(applicationId, target, developerId, content, actor));
        for (Requirement r : scopeRequirements) {
            jdbc.update("INSERT INTO brief_requirement (brief_id, requirement_id, revision) VALUES (?,?,?)",
                brief.getId(), r.getId(), r.getRevision());
        }
        // VYB-0837: the caller's own selection, not scopeCapabilityIds (which resolves
        // an empty selection to "every capability") — empty here is what a later push
        // reads as "generated app-wide", not a narrowed set that happened to be all of them.
        if (capabilityIds != null) {
            for (UUID capId : capabilityIds) {
                jdbc.update("INSERT INTO brief_capability (brief_id, capability_id) VALUES (?,?)", brief.getId(), capId);
            }
        }

        audit.record(actor, "brief.generated", "BRIEF", brief.getId(), null,
            Map.of("applicationId", applicationId.toString(), "target", target.name(),
                   "requirements", scopeRequirements.size(),
                   "excludedNotApproved", inScope.size() - approved.size(),
                   "excludedNoTestCase", excludedNoTestCase,
                   "reviewedElaborations", elaborated));
        return brief;
    }

    /** What a brief for this scope is made of, and what was left out and why. */
    private record Resolved(List<UUID> scopeCapabilityIds, Map<UUID, PlacementLevel> levels, List<Requirement> inScope,
                            List<Requirement> approved, Map<String, Long> excludedByStatus,
                            Map<UUID, List<TestCaseQueryService.RequirementTestCaseRow>> testCasesByRequirement,
                            List<Requirement> scopeRequirements) {}

    private Resolved resolve(UUID applicationId, List<UUID> capabilityIds) {
        List<UUID> scopeCapabilityIds = capabilityIds != null && !capabilityIds.isEmpty()
            ? capabilityIds
            : capabilities.findAllByApplicationIdAndArchivedAtIsNull(applicationId).stream()
                .map(Capability::getId).toList();

        // VYB-0455: the lifecycle is the gate between "a requirement exists" and "an agent
        // is told to build it". A brief used to carry every requirement under the selected
        // capabilities whatever its status — Draft ones nobody had reviewed, and Rejected
        // ones somebody had actively turned down. APPROVED is the one status that means
        // reviewed and not yet built — and, per VYB-0810, the terminal status of this
        // pipeline, so it is also the only status this filter needs to admit.
        // D12: scope is resolved through requirement_scope, which also brings in the
        // application- and product-level rules governing this work. Reading
        // capability_id directly here is what would leave a cross-cutting requirement out
        // of the very brief it constrains.
        List<RequirementScopeService.Scoped> scoped = scopes.governing(applicationId, scopeCapabilityIds);
        Map<UUID, PlacementLevel> levels = scoped.stream()
            .collect(Collectors.toMap(RequirementScopeService.Scoped::requirementId,
                RequirementScopeService.Scoped::level, (a, b) -> a));
        // governing() already excludes soft-deleted rows, so this is a plain lookup.
        List<Requirement> inScope = scoped.isEmpty()
            ? List.of() : requirements.findAllById(levels.keySet());
        List<Requirement> approved = inScope.stream()
            .filter(r -> r.getStatus() == RequirementStatus.APPROVED)
            .toList();
        // Principle 8: what was left out is stated, not silently dropped. A brief that is
        // short because nothing is approved must not look like a brief for a small scope.
        Map<String, Long> excludedByStatus = inScope.stream()
            .filter(r -> r.getStatus() != RequirementStatus.APPROVED)
            .collect(Collectors.groupingBy(r -> r.getStatus().name(), TreeMap::new, Collectors.counting()));

        // VYB-0831: "the test case generated requirements only should load" — approved is
        // necessary but no longer sufficient. A requirement with nothing to verify it yet
        // has nothing for the brief's per-requirement test-case block to show, and briefing
        // it ahead of one that already has coverage would be handing a developer work with
        // no way to tell it apart from work that's already proven out. One bulk query, not
        // one per requirement.
        Map<UUID, List<TestCaseQueryService.RequirementTestCaseRow>> testCasesByRequirement =
            testCases.listForRequirements(approved.stream().map(Requirement::getId).toList());
        List<Requirement> scopeRequirements = approved.stream()
            .filter(r -> testCasesByRequirement.containsKey(r.getId()))
            .toList();
        return new Resolved(scopeCapabilityIds, levels, inScope, approved, excludedByStatus, testCasesByRequirement, scopeRequirements);
    }

    /**
     * VYB-0938: the requirements a brief for this scope would carry (approved, with a test case), in the order it would list
     * them. The same rule as generation, so what is offered for elaboration is exactly what a brief can use it for.
     */
    public List<Requirement> briefable(UUID applicationId, List<UUID> capabilityIds) {
        return resolve(applicationId, capabilityIds).scopeRequirements();
    }

    /**
     * VYB-0836: {@code applicationId} null means every application — the Delivery
     * screen's history, shown even before a product/application is picked, rather than
     * an empty history someone has to scope into before they can see anything was ever
     * generated.
     */
    public List<Brief> history(UUID applicationId) {
        return applicationId == null
            ? briefs.findAllByOrderByGeneratedAtDesc()
            : briefs.findAllByApplicationIdOrderByGeneratedAtDesc(applicationId);
    }

    public Brief get(UUID id) {
        return briefs.findById(id).orElseThrow(NoSuchElementException::new);
    }
}
