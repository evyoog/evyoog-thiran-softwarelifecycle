package com.vyoog.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.RequirementElaborationAdvisor;
import com.vyoog.proposal.AiProposalService;
import com.vyoog.proposal.ProposalKind;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** VYB-0938: asking the AI to elaborate a scope records pending proposals and applies nothing. */
class BriefElaborationDrafterTest {

    private final BriefService briefs = mock(BriefService.class);
    private final RequirementElaborationAdvisor advisor = mock(RequirementElaborationAdvisor.class);
    private final AcceptanceCriterionRepository criteria = mock(AcceptanceCriterionRepository.class);
    private final AiProposalService proposals = mock(AiProposalService.class);
    private final BriefElaborationDrafter drafter = new BriefElaborationDrafter(briefs, advisor, criteria, proposals, new ObjectMapper());
    private final UUID app = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void ready() {
        when(advisor.available()).thenReturn(true);
        when(advisor.modelName()).thenReturn("test-model");
        when(criteria.findAllByRequirementIdOrderByOrdinalAsc(any())).thenReturn(List.of());
        when(proposals.record(any(), any(), any(), any(), any())).thenAnswer(i -> UUID.randomUUID());
    }

    private List<Requirement> requirements(int n) {
        List<Requirement> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Requirement r = mock(Requirement.class);
            when(r.getId()).thenReturn(UUID.randomUUID());
            when(r.getKey()).thenReturn("VY-" + (i + 1));
            when(r.getTitle()).thenReturn("Title " + i);
            when(r.getStatement()).thenReturn("The system shall " + i);
            out.add(r);
        }
        when(briefs.briefable(eq(app), any())).thenReturn(out);
        return out;
    }

    @Test
    void VYB0938_AC5_whenAiIsNotConfiguredItRefusesAndRecordsNothing() {
        when(advisor.available()).thenReturn(false);

        assertThatThrownBy(() -> drafter.draft(app, "Sales", List.of(), actor)).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("not configured");

        verifyNoInteractions(proposals);
        verify(advisor, never()).elaborate(any(), anyList());
    }

    @Test
    void VYB0938_AC5_aScopeWithNothingToBriefIsRefusedInWords() {
        when(briefs.briefable(eq(app), any())).thenReturn(List.of());

        assertThatThrownBy(() -> drafter.draft(app, "Sales", List.of(), actor)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Nothing in this scope would be briefed");
        verify(advisor, never()).elaborate(any(), anyList());
    }

    @Test
    void VYB0938_AC6_eachWellFormedAnswerBecomesAPendingProposalForItsRequirementAndTheModelAndPersonAreRecorded() {
        List<Requirement> scope = requirements(2);
        when(advisor.elaborate(eq("Sales"), anyList())).thenReturn(List.of(
            new RequirementElaborationAdvisor.Elaboration(1, "  Detail for the second.  "),
            new RequirementElaborationAdvisor.Elaboration(0, "Detail for the first.")));

        var drafted = drafter.draft(app, "Sales", List.of(), actor);

        assertThat(drafted.requirementsInScope()).isEqualTo(2);
        assertThat(drafted.proposals()).isEqualTo(2);
        ArgumentCaptor<UUID> req = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<ObjectNode> payload = ArgumentCaptor.forClass(ObjectNode.class);
        verify(proposals, times(2)).record(eq(ProposalKind.BRIEF_ELABORATION), req.capture(), payload.capture(), eq("test-model"), eq(actor));
        assertThat(req.getAllValues()).containsExactly(scope.get(1).getId(), scope.get(0).getId());
        assertThat(payload.getAllValues().get(0).path("detail").asText()).isEqualTo("Detail for the second.");
    }

    @Test
    void VYB0938_AC6_aMalformedOrMisalignedAnswerIsDroppedNotGuessedAt() {
        requirements(1);
        when(advisor.elaborate(eq("Sales"), anyList())).thenReturn(List.of(
            new RequirementElaborationAdvisor.Elaboration(7, "Out of range for a batch of one."),
            new RequirementElaborationAdvisor.Elaboration(0, null),
            new RequirementElaborationAdvisor.Elaboration(0, "   ")));

        var drafted = drafter.draft(app, "Sales", List.of(), actor);

        assertThat(drafted.proposals()).isZero();
        verifyNoInteractions(proposals);
    }

    @Test
    void VYB0938_AC7_requirementsAreSentInBatchesOfEight() {
        requirements(10);
        when(advisor.elaborate(eq("Sales"), anyList())).thenReturn(List.of());

        drafter.draft(app, "Sales", List.of(), actor);

        ArgumentCaptor<List<RequirementElaborationAdvisor.Input>> batches = ArgumentCaptor.forClass(List.class);
        verify(advisor, times(2)).elaborate(eq("Sales"), batches.capture());
        assertThat(batches.getAllValues().get(0)).hasSize(8);
        assertThat(batches.getAllValues().get(1)).hasSize(2);
    }

    @Test
    void VYB0938_AC8_aProviderFailureReachesTheCallerAndNothingFurtherIsRecorded() {
        requirements(1);
        when(advisor.elaborate(any(), anyList())).thenThrow(new AiProviderUnavailableException("temporarily not being called"));

        assertThatThrownBy(() -> drafter.draft(app, "Sales", List.of(), actor)).isInstanceOf(AiProviderUnavailableException.class);

        verifyNoInteractions(proposals);
    }
}
