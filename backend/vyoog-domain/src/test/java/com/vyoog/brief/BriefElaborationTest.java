package com.vyoog.brief;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vyoog.proposal.AiProposalService;
import com.vyoog.evidence.TestCaseQueryService;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserRepository;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementScopeService;
import com.vyoog.requirements.RequirementStatus;
import com.vyoog.trace.TraceGraphService;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0938 (replaces VYB-0817's tests): generating a brief never calls the AI. AI text reaches a brief only as an elaboration
 * a person accepted, for the requirement's current revision, and only when the caller asked for reviewed elaborations.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefElaborationTest {

    @Mock BriefRepository briefs;
    @Mock RequirementRepository requirements;
    @Mock AcceptanceCriterionRepository criteria;
    @Mock CapabilityRepository capabilities;
    @Mock AppUserRepository users;
    @Mock TraceGraphService traceGraph;
    @Mock JdbcTemplate jdbc;
    @Mock AuditService audit;
    @Mock RequirementScopeService scopes;
    @Mock AiProposalService proposals;
    @Mock TestCaseQueryService testCaseQuery;

    BriefService service;
    UUID appId;
    UUID capId;
    UUID developerId;
    UUID approvedId;

    @BeforeEach
    void setUp() {
        service = new BriefService(briefs, requirements, criteria, capabilities, users, traceGraph, jdbc, audit, scopes,
            proposals, testCaseQuery);
        appId = UUID.randomUUID();
        capId = UUID.randomUUID();
        developerId = UUID.randomUUID();
        when(users.findById(developerId)).thenReturn(Optional.of(new AppUser("sub", "d@v.test", "Sibi")));
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn(capId);
        when(cap.getName()).thenReturn("Lead Management");
        when(capabilities.findAllByApplicationIdAndArchivedAtIsNull(appId)).thenReturn(List.of(cap));
        when(capabilities.findAllById(any())).thenReturn(List.of(cap));

        Requirement approved = mock(Requirement.class);
        when(approved.getStatus()).thenReturn(RequirementStatus.APPROVED);
        approvedId = UUID.randomUUID();
        when(approved.getId()).thenReturn(approvedId);
        when(approved.getKey()).thenReturn("VY-1");
        when(approved.getTitle()).thenReturn("Lead capture");
        when(approved.getStatement()).thenReturn("The system shall capture a lead.");
        when(approved.getType()).thenReturn("FUNCTIONAL");
        when(scopes.governing(eq(appId), any())).thenReturn(List.of(
            new RequirementScopeService.Scoped(approvedId, com.vyoog.requirements.PlacementLevel.CAPABILITY)));
        when(requirements.findAllById(any())).thenReturn(List.of(approved));
        when(criteria.findAllByRequirementIdOrderByOrdinalAsc(any())).thenReturn(List.of());
        when(traceGraph.coverageFor(any())).thenReturn(java.util.Map.of());
        when(testCaseQuery.listForRequirements(any())).thenReturn(java.util.Map.of(approvedId, List.of(
            new TestCaseQueryService.RequirementTestCaseRow(
                UUID.randomUUID(), "TC-1", "Captures a lead", "steps", "INDIVIDUAL", "DRAFT"))));
        when(briefs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void VYB0938_AC1_notRequestedNeverConsultsTheProposalsAndTheBriefHasNoAiText() {
        Brief brief = service.generate(appId, "Sales", List.of(), BriefTarget.HUMAN, developerId, null, BriefSection.ALL, false);

        verifyNoInteractions(proposals);
        assertThat(brief.getContent()).doesNotContain("AI elaboration");
    }

    @Test
    void VYB0938_AC2_requestedIncludesTheElaborationAPersonAccepted() {
        when(proposals.acceptedBriefElaborations(any())).thenReturn(java.util.Map.of(approvedId, "Detailed prose about capturing a lead."));

        Brief brief = service.generate(appId, "Sales", List.of(), BriefTarget.HUMAN, developerId, null, BriefSection.ALL, true);

        assertThat(brief.getContent()).contains("Detailed prose about capturing a lead.");
    }

    @Test
    void VYB0938_AC3_requestedWithNothingAcceptedStillGeneratesAndSaysNothingAboutAi() {
        when(proposals.acceptedBriefElaborations(any())).thenReturn(java.util.Map.of());

        Brief brief = service.generate(appId, "Sales", List.of(), BriefTarget.HUMAN, developerId, null, BriefSection.ALL, true);

        assertThat(brief.getContent()).contains("The system shall capture a lead.").doesNotContain("AI elaboration");
        verify(briefs).saveAndFlush(any());
    }

    @Test
    void VYB0938_AC4_onlyTheRequirementsInTheBriefAreLookedUp() {
        when(proposals.acceptedBriefElaborations(any())).thenReturn(java.util.Map.of());

        service.generate(appId, "Sales", List.of(), BriefTarget.HUMAN, developerId, null, BriefSection.ALL, true);

        verify(proposals).acceptedBriefElaborations(List.of(approvedId));
    }
}
