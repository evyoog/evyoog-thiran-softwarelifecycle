package com.vyoog.brief;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.RequirementElaborationAdvisor;
import com.vyoog.requirements.AcceptanceCriterion;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.proposal.AiProposalService;
import com.vyoog.proposal.ProposalKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * VYB-0938 (F30; replaces VYB-0817's in-generation elaboration): asks the AI to elaborate the requirements a brief for a
 * scope would carry, and records each answer as a PENDING proposal. It applies nothing and writes no brief. A person
 * accepts, edits or rejects each proposal at the review endpoint; {@link BriefService#generate} then includes the accepted
 * ones for the requirement's current revision.
 */
@Service
public class BriefElaborationDrafter {

    /** One model call handles several requirements, same reasoning as RequirementBriefAnalyst's batching. */
    static final int BATCH_SIZE = 8;

    public record Drafted(int requirementsInScope, int proposals, List<UUID> proposalIds) {}

    private final BriefService briefs;
    private final RequirementElaborationAdvisor advisor;
    private final AcceptanceCriterionRepository criteria;
    private final AiProposalService proposals;
    private final ObjectMapper json;

    public BriefElaborationDrafter(BriefService briefs, RequirementElaborationAdvisor advisor,
                                   AcceptanceCriterionRepository criteria, AiProposalService proposals, ObjectMapper json) {
        this.briefs = briefs;
        this.advisor = advisor;
        this.criteria = criteria;
        this.proposals = proposals;
        this.json = json;
    }

    /**
     * Refuses rather than doing nothing when the provider is not configured: a person asked for elaboration, and an empty
     * answer would look the same as the AI having nothing to add.
     *
     * @throws AiProviderUnavailableException not configured or failing
     * @throws IllegalStateException          nothing in this scope would be briefed
     */
    public Drafted draft(UUID applicationId, String applicationName, List<UUID> capabilityIds, UUID actor) {
        if (!advisor.available()) {
            throw new AiProviderUnavailableException(
                "AI elaboration is not configured (set AI_ENABLED=true and AI_API_KEY).");
        }
        List<Requirement> scope = briefs.briefable(applicationId, capabilityIds);
        if (scope.isEmpty()) {
            throw new IllegalStateException(
                "Nothing in this scope would be briefed (a brief carries approved requirements that have a test case), so there is nothing to elaborate.");
        }
        List<UUID> ids = new ArrayList<>();
        for (int start = 0; start < scope.size(); start += BATCH_SIZE) {
            List<Requirement> slice = scope.subList(start, Math.min(start + BATCH_SIZE, scope.size()));
            List<RequirementElaborationAdvisor.Input> input = slice.stream()
                .map(r -> new RequirementElaborationAdvisor.Input(r.getKey(), r.getTitle(), r.getStatement(),
                    criteria.findAllByRequirementIdOrderByOrdinalAsc(r.getId()).stream().map(AcceptanceCriterion::getText).toList()))
                .toList();
            for (RequirementElaborationAdvisor.Elaboration e : advisor.elaborate(applicationName, input)) {
                if (!e.isWellFormed() || e.index() >= slice.size()) continue; // a malformed answer is dropped, never guessed at
                ObjectNode payload = json.createObjectNode().put("detail", e.detail().trim());
                ids.add(proposals.record(ProposalKind.BRIEF_ELABORATION, slice.get(e.index()).getId(), payload, advisor.modelName(), actor));
            }
        }
        return new Drafted(scope.size(), ids.size(), ids);
    }

    /** What the Delivery screen shows for a scope: how many requirements, how many have a reviewed elaboration, what is waiting. */
    public record Status(int requirementsInScope, int accepted, List<AiProposalService.Proposal> pending) {}

    public Status status(UUID applicationId, List<UUID> capabilityIds) {
        List<UUID> ids = briefs.briefable(applicationId, capabilityIds).stream().map(Requirement::getId).toList();
        Map<UUID, String> accepted = proposals.acceptedBriefElaborations(ids);
        return new Status(ids.size(), accepted.size(), proposals.pending(ProposalKind.BRIEF_ELABORATION, ids));
    }
}
