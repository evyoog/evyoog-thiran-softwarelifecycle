package com.vyoog.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.TestCaseGenerator;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import com.vyoog.trace.TraceReachability;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** VYB-0824: gathers a requirement's own content and its direct trace links (both directions) before asking the agent. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestCaseSuggestionServiceTest {

    @Mock RequirementRepository requirements;
    @Mock AcceptanceCriterionRepository criteria;
    @Mock TraceGraphService traceGraph;
    @Mock IngestedCommitRepository commits;
    @Mock TestCaseGenerator generator;

    TestCaseSuggestionService service;
    UUID requirementId;
    UUID upstreamId;
    UUID downstreamId;

    @BeforeEach
    void setUp() {
        service = new TestCaseSuggestionService(requirements, criteria, traceGraph, commits, generator);
        requirementId = UUID.randomUUID();
        upstreamId = UUID.randomUUID();
        downstreamId = UUID.randomUUID();

        Requirement requirement = mock(Requirement.class);
        when(requirement.getKey()).thenReturn("VY-1");
        when(requirement.getTitle()).thenReturn("Lead capture");
        when(requirement.getStatement()).thenReturn("The system shall capture a lead.");
        when(requirements.findById(requirementId)).thenReturn(Optional.of(requirement));
        when(criteria.findAllByRequirementIdOrderByOrdinalAsc(requirementId)).thenReturn(List.of());
        // No linked commits by default — VYB0829 tests override this explicitly.
        when(traceGraph.linksFor(any(), any())).thenReturn(new TraceGraphService.DirectLinks(List.of(), List.of()));

        when(generator.available()).thenReturn(true);
        when(generator.modelName()).thenReturn("gpt-4o-mini");
        when(generator.generate(any(), any())).thenReturn(List.of());
    }

    @Test
    void VYB0824_AC1_noTraceLinksProducesIndividualOnlyRequest() {
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());

        var result = service.suggest(requirementId);

        assertThat(result.relatedRequirements()).isEmpty();
        verify(generator).generate(any(), eq(List.of()));
    }

    @Test
    void VYB0829_AC1_noLinkedCommitsMeansEmptyMessageList() {
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());

        service.suggest(requirementId);

        var inputCaptor = org.mockito.ArgumentCaptor.forClass(TestCaseGenerator.RequirementInput.class);
        verify(generator).generate(inputCaptor.capture(), any());
        assertThat(inputCaptor.getValue().linkedCommitMessages()).isEmpty();
    }

    @Test
    void VYB0829_AC2_commitsLinkedByImplementsAreCollectedAsMessages() {
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());

        UUID commitId = UUID.randomUUID();
        TraceLink implementsLink = new TraceLink(TraceObjectType.CODE, commitId, TraceObjectType.REQUIREMENT, requirementId,
            TraceLinkType.IMPLEMENTS, null);
        when(traceGraph.linksFor(TraceObjectType.REQUIREMENT, requirementId))
            .thenReturn(new TraceGraphService.DirectLinks(List.of(), List.of(implementsLink)));
        IngestedCommit commit = mock(IngestedCommit.class);
        when(commit.getMessage()).thenReturn("Capture lead email\n\nRequirement: VY-1");
        when(commits.findById(commitId)).thenReturn(Optional.of(commit));

        service.suggest(requirementId);

        var inputCaptor = org.mockito.ArgumentCaptor.forClass(TestCaseGenerator.RequirementInput.class);
        verify(generator).generate(inputCaptor.capture(), any());
        assertThat(inputCaptor.getValue().linkedCommitMessages()).containsExactly("Capture lead email\n\nRequirement: VY-1");
    }

    @Test
    void VYB0829_AC3_nonImplementsOrNonCodeLinksAreIgnored() {
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());

        // A REQUIREMENT->REQUIREMENT SATISFIES link incoming — not a commit, must be ignored.
        TraceLink notACommit = new TraceLink(TraceObjectType.REQUIREMENT, UUID.randomUUID(), TraceObjectType.REQUIREMENT,
            requirementId, TraceLinkType.SATISFIES, null);
        when(traceGraph.linksFor(TraceObjectType.REQUIREMENT, requirementId))
            .thenReturn(new TraceGraphService.DirectLinks(List.of(), List.of(notACommit)));

        service.suggest(requirementId);

        var inputCaptor = org.mockito.ArgumentCaptor.forClass(TestCaseGenerator.RequirementInput.class);
        verify(generator).generate(inputCaptor.capture(), any());
        assertThat(inputCaptor.getValue().linkedCommitMessages()).isEmpty();
    }

    @Test
    void VYB0824_AC2_upstreamAndDownstreamBothCollected() {
        Requirement upstream = mock(Requirement.class);
        when(upstream.getKey()).thenReturn("VY-UP");
        when(upstream.getTitle()).thenReturn("Upstream req");
        when(upstream.getStatement()).thenReturn("Upstream statement.");
        when(requirements.findById(upstreamId)).thenReturn(Optional.of(upstream));

        Requirement downstream = mock(Requirement.class);
        when(downstream.getKey()).thenReturn("VY-DOWN");
        when(downstream.getTitle()).thenReturn("Downstream req");
        when(downstream.getStatement()).thenReturn("Downstream statement.");
        when(requirements.findById(downstreamId)).thenReturn(Optional.of(downstream));

        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1))
            .thenReturn(List.of(new TraceReachability(TraceObjectType.REQUIREMENT, upstreamId, 1, List.of())));
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1))
            .thenReturn(List.of(new TraceReachability(TraceObjectType.REQUIREMENT, downstreamId, 1, List.of())));

        var result = service.suggest(requirementId);

        assertThat(result.relatedRequirements()).extracting(TestCaseGenerator.RelatedRequirement::key)
            .containsExactlyInAnyOrder("VY-UP", "VY-DOWN");
        var relatedCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(generator).generate(any(), relatedCaptor.capture());
        @SuppressWarnings("unchecked")
        List<TestCaseGenerator.RelatedRequirement> related = relatedCaptor.getValue();
        assertThat(related).extracting(TestCaseGenerator.RelatedRequirement::direction)
            .containsExactlyInAnyOrder("UPSTREAM", "DOWNSTREAM");
    }

    @Test
    void VYB0824_AC3_unavailableRefusesBeforeCallingGenerate() {
        when(generator.available()).thenReturn(false);

        assertThatThrownBy(() -> service.suggest(requirementId))
            .isInstanceOf(AiProviderUnavailableException.class);

        verify(generator, never()).generate(any(), any());
    }

    @Test
    void VYB0824_AC4_malformedSuggestionDropped() {
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        when(generator.generate(any(), any())).thenReturn(List.of(
            new TestCaseGenerator.Suggestion(TestCaseGenerator.Category.INDIVIDUAL, "", "blank title", "r"),
            new TestCaseGenerator.Suggestion(TestCaseGenerator.Category.INDIVIDUAL, "Real one", "d", "r")));

        var result = service.suggest(requirementId);

        assertThat(result.suggestions()).extracting(TestCaseGenerator.Suggestion::title).containsExactly("Real one");
    }

    @Test
    void VYB0826_AC1_bulkExpandsToDirectDependenciesOfEachSelected() {
        Requirement upstream = mock(Requirement.class);
        when(upstream.getKey()).thenReturn("VY-UP");
        when(upstream.getTitle()).thenReturn("Upstream req");
        when(upstream.getStatement()).thenReturn("Upstream statement.");
        when(requirements.findById(upstreamId)).thenReturn(Optional.of(upstream));

        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1))
            .thenReturn(List.of(new TraceReachability(TraceObjectType.REQUIREMENT, upstreamId, 1, List.of())));
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        // suggest(upstreamId) is called too — it needs its own (empty) trace stubs.
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, upstreamId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, upstreamId, 1)).thenReturn(List.of());

        var result = service.suggestBulk(List.of(requirementId));

        assertThat(result.perRequirement()).extracting(TestCaseSuggestionService.RequirementSuggestions::requirementId)
            .containsExactlyInAnyOrder(requirementId, upstreamId);
        var selectedOne = result.perRequirement().stream().filter(rs -> rs.requirementId().equals(requirementId)).findFirst().orElseThrow();
        var pulledIn = result.perRequirement().stream().filter(rs -> rs.requirementId().equals(upstreamId)).findFirst().orElseThrow();
        assertThat(selectedOne.pulledInAsDependency()).isFalse();
        assertThat(pulledIn.pulledInAsDependency()).isTrue();
    }

    @Test
    void VYB0826_AC2_unavailableRefusesBeforeExpandingOrCallingGenerate() {
        when(generator.available()).thenReturn(false);

        assertThatThrownBy(() -> service.suggestBulk(List.of(requirementId)))
            .isInstanceOf(AiProviderUnavailableException.class);

        verify(traceGraph, never()).upstream(any(), any(), anyInt());
        verify(traceGraph, never()).downstream(any(), any(), anyInt());
        verify(generator, never()).generate(any(), any());
    }

    @Test
    void VYB0826_AC3_selectingTwoRequirementsWithSharedDependencyGeneratesItOnce() {
        Requirement shared = mock(Requirement.class);
        when(shared.getKey()).thenReturn("VY-SHARED");
        when(shared.getTitle()).thenReturn("Shared dependency");
        when(shared.getStatement()).thenReturn("Shared statement.");
        when(requirements.findById(downstreamId)).thenReturn(Optional.of(shared));

        // Both selected requirements depend on the same downstream one.
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, 1))
            .thenReturn(List.of(new TraceReachability(TraceObjectType.REQUIREMENT, downstreamId, 1, List.of())));
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, upstreamId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, upstreamId, 1))
            .thenReturn(List.of(new TraceReachability(TraceObjectType.REQUIREMENT, downstreamId, 1, List.of())));
        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, downstreamId, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, downstreamId, 1)).thenReturn(List.of());
        // The second selected requirement ("upstreamId" here is just a second selected id) needs its own requirement row too.
        Requirement second = mock(Requirement.class);
        when(second.getKey()).thenReturn("VY-2");
        when(second.getTitle()).thenReturn("Second selected");
        when(second.getStatement()).thenReturn("Second statement.");
        when(requirements.findById(upstreamId)).thenReturn(Optional.of(second));

        var result = service.suggestBulk(List.of(requirementId, upstreamId));

        assertThat(result.perRequirement()).extracting(TestCaseSuggestionService.RequirementSuggestions::requirementId)
            .containsExactlyInAnyOrder(requirementId, upstreamId, downstreamId);
        // generate() called exactly once per distinct target, not once per (selected, dependency) pair.
        verify(generator, org.mockito.Mockito.times(3)).generate(any(), any());
    }

    @Test
    void VYB0830_AC1_transitiveChainPullsInEverythingConnected() {
        // req-2 depends on req-1, req-1 depends on req-0, and req-3/req-4 also depend
        // on req-1 — selecting only req-2 must still pull in all five.
        UUID req0 = UUID.randomUUID();
        UUID req1 = UUID.randomUUID();
        UUID req2 = requirementId;
        UUID req3 = UUID.randomUUID();
        UUID req4 = UUID.randomUUID();

        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, req2, 1)).thenReturn(reach(req1));
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, req2, 1)).thenReturn(List.of());

        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, req1, 1)).thenReturn(reach(req0));
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, req1, 1)).thenReturn(reach(req2, req3, req4));

        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, req0, 1)).thenReturn(List.of());
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, req0, 1)).thenReturn(reach(req1));

        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, req3, 1)).thenReturn(reach(req1));
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, req3, 1)).thenReturn(List.of());

        when(traceGraph.upstream(TraceObjectType.REQUIREMENT, req4, 1)).thenReturn(reach(req1));
        when(traceGraph.downstream(TraceObjectType.REQUIREMENT, req4, 1)).thenReturn(List.of());

        requirementNamed(req0, "VY-0");
        requirementNamed(req1, "VY-1-DEP");
        requirementNamed(req3, "VY-3");
        requirementNamed(req4, "VY-4");
        // req2 (== requirementId) is already stubbed with key "VY-1" in setUp.

        var cluster = service.dependencyCluster(List.of(req2));

        assertThat(cluster.capped()).isFalse();
        assertThat(cluster.members()).extracting(TestCaseSuggestionService.ClusterMember::requirementId)
            .containsExactlyInAnyOrder(req0, req1, req2, req3, req4);
        assertThat(cluster.members()).filteredOn(m -> m.requirementId().equals(req2))
            .extracting(TestCaseSuggestionService.ClusterMember::selected).containsExactly(true);
        assertThat(cluster.members()).filteredOn(m -> !m.requirementId().equals(req2))
            .extracting(TestCaseSuggestionService.ClusterMember::selected).containsOnly(false);
    }

    @Test
    void VYB0830_AC2_capsAtMaxClusterSizeAndReportsCapped() {
        // A chain of 45 requirements, each one's upstream is the next — walking from
        // the first must stop at the 40-member cap, not walk the whole chain.
        List<UUID> chain = new java.util.ArrayList<>();
        for (int i = 0; i < 45; i++) chain.add(UUID.randomUUID());
        for (int i = 0; i < chain.size(); i++) {
            UUID id = chain.get(i);
            List<TraceReachability> up = i + 1 < chain.size() ? reach(chain.get(i + 1)) : List.of();
            when(traceGraph.upstream(TraceObjectType.REQUIREMENT, id, 1)).thenReturn(up);
            when(traceGraph.downstream(TraceObjectType.REQUIREMENT, id, 1)).thenReturn(List.of());
        }
        Requirement generic = mock(Requirement.class);
        when(generic.getKey()).thenReturn("VY-X");
        when(generic.getTitle()).thenReturn("X");
        when(requirements.findById(any())).thenReturn(Optional.of(generic));

        var cluster = service.dependencyCluster(List.of(chain.get(0)));

        assertThat(cluster.capped()).isTrue();
        assertThat(cluster.members()).hasSizeLessThanOrEqualTo(40);
    }

    private static List<TraceReachability> reach(UUID... ids) {
        return java.util.Arrays.stream(ids)
            .map(id -> new TraceReachability(TraceObjectType.REQUIREMENT, id, 1, List.of()))
            .toList();
    }

    private void requirementNamed(UUID id, String key) {
        Requirement r = mock(Requirement.class);
        when(r.getKey()).thenReturn(key);
        when(r.getTitle()).thenReturn(key + " title");
        when(requirements.findById(id)).thenReturn(Optional.of(r));
    }
}
