package com.vyoog.brief;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vyoog.ai.RequirementElaborationAdvisor;
import com.vyoog.evidence.TestCaseQueryService;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserRepository;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementScopeService;
import com.vyoog.requirements.RequirementRepository;
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
 * VYB-0455: a brief with nothing in it is refused, not produced. The APPROVED gate made
 * this reachable for the first time — before it, "everything under these capabilities"
 * was almost never empty, so the case never came up.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefEmptyScopeTest {

    @Mock BriefRepository briefs;
    @Mock RequirementRepository requirements;
    @Mock AcceptanceCriterionRepository criteria;
    @Mock CapabilityRepository capabilities;
    @Mock AppUserRepository users;
    @Mock TraceGraphService traceGraph;
    @Mock JdbcTemplate jdbc;
    @Mock AuditService audit;
    @Mock RequirementScopeService scopes;
    @Mock RequirementElaborationAdvisor elaborationAdvisor;
    @Mock TestCaseQueryService testCaseQuery;

    BriefService service;
    UUID appId;
    UUID capId;
    UUID developerId;

    @BeforeEach
    void setUp() {
        service = new BriefService(briefs, requirements, criteria, capabilities, users, traceGraph, jdbc, audit, scopes,
            elaborationAdvisor, testCaseQuery);
        appId = UUID.randomUUID();
        capId = UUID.randomUUID();
        developerId = UUID.randomUUID();
        when(users.findById(developerId)).thenReturn(Optional.of(new AppUser("sub", "d@v.test", "Sibi")));
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn(capId);
        when(cap.getName()).thenReturn("Lead Management");
        when(capabilities.findAllByApplicationIdAndArchivedAtIsNull(appId)).thenReturn(List.of(cap));
        when(capabilities.findAllById(any())).thenReturn(List.of(cap));
    }

    private Requirement draft() {
        return new Requirement("VY-1", "Lead capture", "The system shall capture a lead.", null);
    }

    /**
     * Stubs a scope holding exactly these requirements, all at capability level.
     *
     * <p>Requirement ids are assigned by Hibernate, so they are null on unsaved entities;
     * these tests only ever need the *set* to be right, and the service keys its level map
     * by id, so a stable synthetic id per row is enough.
     */
    private void inScope(List<Requirement> rows) {
        List<RequirementScopeService.Scoped> scopedRows = new java.util.ArrayList<>();
        java.util.Map<UUID, Requirement> byId = new java.util.LinkedHashMap<>();
        for (Requirement r : rows) {
            UUID id = UUID.randomUUID();
            org.springframework.test.util.ReflectionTestUtils.setField(r, "id", id);
            scopedRows.add(new RequirementScopeService.Scoped(id, com.vyoog.requirements.PlacementLevel.CAPABILITY));
            byId.put(id, r);
        }
        when(scopes.governing(eq(appId), any())).thenReturn(scopedRows);
        when(requirements.findAllById(any())).thenReturn(List.copyOf(byId.values()));
    }

    @Test
    void VYB0455_AC1_sixteenDraftsAndNothingApprovedIsRefusedWithACountAndARemedy() {
        inScope(List.of(draft(), draft(), draft()));

        assertThatThrownBy(() -> service.generate(appId, "Sales", List.of(), BriefTarget.CLAUDE_CODE, developerId, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("None of the 3 requirements in scope are approved")
            .hasMessageContaining("Approve them in Requirements first");

        // Nothing was written — no orphan brief row left behind by the refusal.
        verify(briefs, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void VYB0455_AC1_anEmptyCapabilitySaysSoRatherThanBlamingApproval() {
        inScope(List.of());

        assertThatThrownBy(() -> service.generate(appId, "Sales", List.of(), BriefTarget.CLAUDE_CODE, developerId, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("hold no requirements");
    }

    @Test
    void VYB0831_AC1_oneApprovedRequirementWithATestCaseIsEnoughToGenerate() {
        // Mocked rather than walked to APPROVED: transitionTo is package-private in
        // com.vyoog.requirements on purpose, and widening it so a test in another package
        // can reach it would trade real encapsulation for test convenience.
        Requirement approved = mock(Requirement.class);
        when(approved.getStatus()).thenReturn(RequirementStatus.APPROVED);
        when(approved.getId()).thenReturn(UUID.randomUUID());
        when(approved.getKey()).thenReturn("VY-1");
        when(approved.getTitle()).thenReturn("Lead capture");
        when(approved.getStatement()).thenReturn("The system shall capture a lead.");
        when(approved.getType()).thenReturn("FUNCTIONAL");
        // A mock cannot have its id set by reflection, so this scope is stubbed directly
        // rather than through inScope().
        Requirement stillDraft = draft();
        UUID draftId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(stillDraft, "id", draftId);
        UUID approvedId = approved.getId();
        when(scopes.governing(eq(appId), any())).thenReturn(List.of(
            new RequirementScopeService.Scoped(approvedId, com.vyoog.requirements.PlacementLevel.CAPABILITY),
            new RequirementScopeService.Scoped(draftId, com.vyoog.requirements.PlacementLevel.CAPABILITY)));
        when(requirements.findAllById(any())).thenReturn(List.of(approved, stillDraft));
        when(criteria.findAllByRequirementIdOrderByOrdinalAsc(any())).thenReturn(List.of());
        when(traceGraph.coverageFor(any())).thenReturn(java.util.Map.of());
        when(testCaseQuery.listForRequirements(any())).thenReturn(java.util.Map.of(approvedId, List.of(
            new TestCaseQueryService.RequirementTestCaseRow(
                UUID.randomUUID(), "TC-1", "Rejects a duplicate lead", "steps", "INDIVIDUAL", "DRAFT"))));
        when(briefs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.generate(appId, "Sales", List.of(), BriefTarget.CLAUDE_CODE, developerId, null))
            .doesNotThrowAnyException();
    }

    @Test
    void VYB0831_AC2_anApprovedRequirementWithNoTestCaseIsRefusedWithACountAndARemedy() {
        // VYB-0831: approved is necessary but no longer sufficient — with no test case
        // anywhere in scope, this must refuse the same way "nothing approved" already
        // does, naming the real reason rather than reusing the approval message.
        Requirement approved = mock(Requirement.class);
        when(approved.getStatus()).thenReturn(RequirementStatus.APPROVED);
        UUID approvedId = UUID.randomUUID();
        when(approved.getId()).thenReturn(approvedId);
        when(scopes.governing(eq(appId), any())).thenReturn(List.of(
            new RequirementScopeService.Scoped(approvedId, com.vyoog.requirements.PlacementLevel.CAPABILITY)));
        when(requirements.findAllById(any())).thenReturn(List.of(approved));
        when(testCaseQuery.listForRequirements(any())).thenReturn(java.util.Map.of());

        assertThatThrownBy(() -> service.generate(appId, "Sales", List.of(), BriefTarget.CLAUDE_CODE, developerId, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("None of the 1 approved requirements in scope have a test case yet")
            .hasMessageContaining("Quality → Verification");

        verify(briefs, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), anyString(), anyString(), any(), any(), any());
    }
}
