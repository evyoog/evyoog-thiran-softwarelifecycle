package com.vyoog.evidence;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.TestCaseGenerator;
import com.vyoog.requirements.AcceptanceCriterion;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import com.vyoog.trace.TraceReachability;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * VYB-0824: gathers a requirement's own content plus its directly trace-linked
 * requirements (both directions — the same "depends on" relationships {@code
 * DesignService.generateFrom} draws its diagram edges from, direct links only, not the
 * full transitive closure) and hands both to {@link TestCaseGenerator}. Kept separate
 * from the generator itself so the generator (in {@code com.vyoog.ai}) stays free of
 * domain-entity imports, the same reason {@code com.vyoog.brief.BriefService} exists
 * alongside {@code RequirementElaborationAdvisor} rather than folding gathering logic
 * into the advisor.
 */
@Service
public class TestCaseSuggestionService {

    /** Direct trace links only — same reasoning as DesignService.generateFrom's edges. */
    private static final int DIRECT_DEPTH = 1;

    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;
    private final TraceGraphService trace;
    private final IngestedCommitRepository commits;
    private final TestCaseGenerator generator;

    public TestCaseSuggestionService(RequirementRepository requirements, AcceptanceCriterionRepository criteria,
                                      TraceGraphService trace, IngestedCommitRepository commits, TestCaseGenerator generator) {
        this.requirements = requirements;
        this.criteria = criteria;
        this.trace = trace;
        this.commits = commits;
        this.generator = generator;
    }

    /**
     * @param relatedRequirements every requirement directly trace-linked to the one
     *     suggestions were requested for, both directions — echoed back (not just a
     *     count/boolean) so the caller can show which specific requirements the
     *     DEPENDENCY suggestions are grounded in, not just that some exist.
     */
    public record Result(List<TestCaseGenerator.Suggestion> suggestions,
                          List<TestCaseGenerator.RelatedRequirement> relatedRequirements, String model) {}

    public Result suggest(UUID requirementId) {
        if (!generator.available()) {
            throw new AiProviderUnavailableException(
                "AI test case suggestions are not configured (set AI_ENABLED=true and AI_API_KEY).");
        }

        Requirement requirement = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        var input = new TestCaseGenerator.RequirementInput(requirement.getKey(), requirement.getTitle(),
            requirement.getStatement(),
            criteria.findAllByRequirementIdOrderByOrdinalAsc(requirementId).stream()
                .map(AcceptanceCriterion::getText).toList(),
            linkedCommitMessages(requirementId));

        List<TestCaseGenerator.RelatedRequirement> related = new ArrayList<>();
        collect(related, requirementId, "UPSTREAM", trace.upstream(TraceObjectType.REQUIREMENT, requirementId, DIRECT_DEPTH));
        collect(related, requirementId, "DOWNSTREAM", trace.downstream(TraceObjectType.REQUIREMENT, requirementId, DIRECT_DEPTH));

        List<TestCaseGenerator.Suggestion> suggestions = generator.generate(input, related).stream()
            .filter(TestCaseGenerator.Suggestion::isWellFormed)
            .toList();
        return new Result(suggestions, related, generator.modelName());
    }

    /**
     * VYB-0829: every commit whose {@code Requirement:} trailer links it here
     * (VYB-0316) — a {@code CODE --IMPLEMENTS--> REQUIREMENT} trace link, incoming from
     * this requirement's own perspective. Message text only; no diff/file content
     * exists anywhere in this platform to read.
     */
    private List<String> linkedCommitMessages(UUID requirementId) {
        List<String> messages = new ArrayList<>();
        for (TraceLink link : trace.linksFor(TraceObjectType.REQUIREMENT, requirementId).incoming()) {
            if (link.getFromType() != TraceObjectType.CODE || link.getLinkType() != TraceLinkType.IMPLEMENTS) continue;
            commits.findById(link.getFromId()).map(IngestedCommit::getMessage)
                .filter(m -> m != null && !m.isBlank())
                .ifPresent(messages::add);
        }
        return messages;
    }

    private void collect(List<TestCaseGenerator.RelatedRequirement> out, UUID selfId, String direction,
                          List<TraceReachability> hops) {
        for (TraceReachability hop : hops) {
            if (hop.type() != TraceObjectType.REQUIREMENT || hop.id().equals(selfId)) continue;
            requirements.findById(hop.id()).ifPresent(r ->
                out.add(new TestCaseGenerator.RelatedRequirement(r.getKey(), r.getTitle(), r.getStatement(), direction)));
        }
    }

    /**
     * @param pulledInAsDependency false for a requirement the caller actually selected;
     *     true for one added only because it's a direct dependency (either direction) of
     *     a selected one — the caller uses this to label a card "pulled in because it
     *     depends on VY-x" rather than implying the person picked it themselves.
     */
    public record RequirementSuggestions(UUID requirementId, String requirementKey, String requirementTitle,
                                          List<TestCaseGenerator.Suggestion> suggestions,
                                          List<TestCaseGenerator.RelatedRequirement> relatedRequirements,
                                          boolean pulledInAsDependency) {}

    public record BulkResult(List<RequirementSuggestions> perRequirement, String model) {}

    /** A requirement in a computed cluster — {@code selected} false means it was pulled in only because it's connected to a selected one. */
    public record ClusterMember(UUID requirementId, String key, String title, boolean selected) {}

    /** One direct REQUIREMENT↔REQUIREMENT trace link between two members of the same cluster. */
    public record ClusterEdge(String fromKey, String toKey, String linkType) {}

    /** @param capped true if the walk stopped at {@link #MAX_CLUSTER_SIZE} before exhausting the connected component. */
    public record Cluster(List<ClusterMember> members, List<ClusterEdge> edges, boolean capped) {}

    /**
     * VYB-0830: a hard ceiling on how large a connected component {@link #dependencyCluster}
     * will walk before stopping — a densely-linked register could otherwise turn one
     * click into an unbounded number of downstream OpenAI calls. Disclosed via {@link
     * Cluster#capped}, never a silent truncation.
     */
    private static final int MAX_CLUSTER_SIZE = 40;

    /**
     * VYB-0830: the full connected component containing every selected requirement —
     * not just its direct neighbours. Walks {@link #trace}'s upstream/downstream, one
     * hop at a time, from every requirement discovered so far, repeating until nothing
     * new is found (or the cap is hit): if A depends on B and B depends on C, selecting
     * only A pulls in both B and C, and anything else that depends on B pulls in too —
     * the same "one requirement depending on another means include that one too,
     * repeatedly" rule the product owner asked for. Makes no AI call and needs none —
     * this is the cheap preview a generation flow shows before anyone commits to
     * actually generating anything.
     */
    public Cluster dependencyCluster(List<UUID> selectedIds) {
        Set<UUID> selected = new LinkedHashSet<>(selectedIds);
        Set<UUID> visited = new LinkedHashSet<>(selected);
        Deque<UUID> frontier = new ArrayDeque<>(selected);
        boolean capped = false;

        while (!frontier.isEmpty()) {
            if (visited.size() >= MAX_CLUSTER_SIZE) { capped = true; break; }
            UUID id = frontier.poll();
            List<UUID> neighbours = new ArrayList<>();
            collectRequirementIds(neighbours, trace.upstream(TraceObjectType.REQUIREMENT, id, DIRECT_DEPTH));
            collectRequirementIds(neighbours, trace.downstream(TraceObjectType.REQUIREMENT, id, DIRECT_DEPTH));
            for (UUID neighbour : neighbours) {
                if (visited.size() >= MAX_CLUSTER_SIZE) { capped = true; break; }
                if (visited.add(neighbour)) frontier.add(neighbour);
            }
        }

        List<ClusterMember> members = new ArrayList<>();
        Map<UUID, String> keyById = new LinkedHashMap<>();
        for (UUID id : visited) {
            requirements.findById(id).ifPresent(r -> {
                members.add(new ClusterMember(id, r.getKey(), r.getTitle(), selected.contains(id)));
                keyById.put(id, r.getKey());
            });
        }

        List<ClusterEdge> edges = new ArrayList<>();
        Set<UUID> seenLinks = new LinkedHashSet<>();
        for (UUID id : visited) {
            for (TraceLink link : trace.linksFor(TraceObjectType.REQUIREMENT, id).outgoing()) {
                if (link.getToType() != TraceObjectType.REQUIREMENT || !visited.contains(link.getToId())) continue;
                if (!seenLinks.add(link.getId())) continue;
                edges.add(new ClusterEdge(keyById.get(link.getFromId()), keyById.get(link.getToId()), link.getLinkType().name()));
            }
        }

        return new Cluster(members, edges, capped);
    }

    private void collectRequirementIds(List<UUID> out, List<TraceReachability> hops) {
        for (TraceReachability hop : hops) {
            if (hop.type() == TraceObjectType.REQUIREMENT) out.add(hop.id());
        }
    }

    /**
     * VYB-0826/0830: generating for a bulk selection also generates for the selection's
     * whole connected dependency component ({@link #dependencyCluster}), not just its
     * direct neighbours — so suggesting for a requirement covers everything it's
     * connected to, transitively, without a second manual selection. Each member still
     * goes through {@link #suggest(UUID)} unchanged — same refusal, same filtering, same
     * per-requirement DEPENDENCY grounding — this only decides *which* requirements get
     * a call, not how any one of them is generated. One OpenAI call per member,
     * sequential: parallelising would need the same per-run budget guard {@code
     * AiUsageTracker} gives the automatic detection sweep, which no on-demand,
     * explicitly-user-triggered advisor in this codebase uses today (see {@code
     * RequirementRewriteAdvisor}/{@code RequirementElaborationAdvisor}) — a large
     * cluster is simply slower, not silently truncated (beyond the cluster-size cap
     * itself, which the caller can see via a fresh {@link #dependencyCluster} call).
     *
     * @throws AiProviderUnavailableException if the provider is unconfigured, checked
     *     once up front so a large selection fails fast rather than partway through
     */
    public BulkResult suggestBulk(List<UUID> selectedIds) {
        if (!generator.available()) {
            throw new AiProviderUnavailableException(
                "AI test case suggestions are not configured (set AI_ENABLED=true and AI_API_KEY).");
        }

        Cluster cluster = dependencyCluster(selectedIds);
        List<RequirementSuggestions> out = new ArrayList<>();
        for (ClusterMember member : cluster.members()) {
            Result result = suggest(member.requirementId());
            out.add(new RequirementSuggestions(member.requirementId(), member.key(), member.title(),
                result.suggestions(), result.relatedRequirements(), !member.selected()));
        }
        return new BulkResult(out, generator.modelName());
    }
}
