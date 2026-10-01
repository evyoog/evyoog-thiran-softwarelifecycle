package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.changerequest.ChangeRequest;
import com.vyoog.changerequest.ChangeRequestService;
import com.vyoog.changerequest.ChangeRequestState;
import com.vyoog.requirements.Requirement;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0907 (F11): the change-request apply path: the only way to change an approved requirement.
 * Raise, impact, decide, edit through the change request, mark applied, and the suspect links it leaves.
 */
class ChangeRequestApplyIT extends IntegrationTestBase {

    @Autowired ChangeRequestService changeRequests;
    @Autowired TraceGraphService trace;

    private Portfolio p;
    private UUID author, admin;
    private Requirement approved;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
        approved = approved(newRequirement(p, author), author, admin);
    }

    private ChangeRequest raise(Requirement... scope) {
        return changeRequests.raise(unique("CR"), "the rule changed", java.util.Arrays.stream(scope).map(Requirement::getId).toList(), author);
    }

    private Requirement applyEdit(ChangeRequest cr, Requirement r, String statement) {
        changeRequests.assertCanEdit(cr.getId(), r.getId());
        return requirementService.applyChangeRequestEdit(r.getId(), r.getRevision(), r.getTitle(), statement, r.getType(),
            r.getPriority(), r.getCapabilityId(), admin);
    }

    @Test
    void VYB0907_AC1_aChangeRequestNeedsRequirementsGetsItsOwnKeyAndStoresItsScope() {
        assertThatThrownBy(() -> changeRequests.raise("empty", "why", List.of(), author)).isInstanceOf(IllegalArgumentException.class);
        ChangeRequest one = raise(approved);
        ChangeRequest two = raise(approved);
        assertThat(one.getKey()).isNotEqualTo(two.getKey());
        assertThat(one.getState()).isEqualTo(ChangeRequestState.OPEN);
        assertThat(changeRequests.scope(one.getId())).containsExactly(approved.getId());
        assertThat(auditCount(one.getId(), "change_request.raised")).isEqualTo(1);
    }

    @Test
    void VYB0907_AC2_nothingEditsAnApprovedRequirementUntilTheChangeRequestIsApprovedAndNamesIt() {
        ChangeRequest cr = raise(approved);
        Requirement other = newRequirement(p, author);

        assertThatThrownBy(() -> changeRequests.assertCanEdit(cr.getId(), approved.getId()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not approved");

        changeRequests.decide(cr.getId(), true, admin);
        changeRequests.assertCanEdit(cr.getId(), approved.getId());                       // now allowed
        assertThatThrownBy(() -> changeRequests.assertCanEdit(cr.getId(), other.getId()))  // but only for what it names
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not in the scope");
    }

    @Test
    void VYB0907_AC3_aRejectedOrAlreadyDecidedChangeRequestCannotBeDecidedAgain() {
        ChangeRequest cr = raise(approved);
        changeRequests.decide(cr.getId(), false, admin);
        assertThatThrownBy(() -> changeRequests.decide(cr.getId(), true, admin))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
        assertThatThrownBy(() -> changeRequests.assertCanEdit(cr.getId(), approved.getId()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void VYB0907_AC4_theApprovedEditMakesANewRevisionAuditedAsViaChangeRequestAndTheRequirementStaysApproved() {
        ChangeRequest cr = raise(approved);
        changeRequests.decide(cr.getId(), true, admin);

        Requirement edited = applyEdit(cr, approved, "The system shall comply with the revised rule.");

        assertThat(edited.getRevision()).isEqualTo(2);
        assertThat(revisionRows(edited.getId())).isEqualTo(2);
        assertThat(auditCount(edited.getId(), "requirement.revised_via_change_request")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, edited.getId())).isEqualTo("APPROVED");
    }

    @Test
    void VYB0907_AC5_theChangeRequestIsMarkedAppliedOnlyAfterItWasApproved() {
        ChangeRequest cr = raise(approved);
        assertThatThrownBy(() -> changeRequests.markAppliedIfComplete(cr.getId(), admin))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("approved");

        changeRequests.decide(cr.getId(), true, admin);
        applyEdit(cr, approved, "Edited through the change request.");
        assertThat(changeRequests.markAppliedIfComplete(cr.getId(), admin).getState()).isEqualTo(ChangeRequestState.APPLIED);
        assertThat(auditCount(cr.getId(), "change_request.applied")).isEqualTo(1);
    }

    @Test
    void VYB0907_AC6_impactCountsTheScopePlusEverythingDownstreamInTheTraceGraph() {
        Requirement child = newRequirement(p, author);
        Requirement grandchild = newRequirement(p, author);
        trace.createLink(TraceObjectType.REQUIREMENT, approved.getId(), TraceObjectType.REQUIREMENT, child.getId(), TraceLinkType.DERIVES, author);
        trace.createLink(TraceObjectType.REQUIREMENT, child.getId(), TraceObjectType.REQUIREMENT, grandchild.getId(), TraceLinkType.DERIVES, author);

        ChangeRequest cr = raise(approved);
        var impact = changeRequests.computeImpact(cr.getId());

        assertThat(impact.requirements()).isEqualTo(3);
        assertThat(impact.applications()).isEqualTo(1);
        assertThat(impact.tests()).isZero();
        assertThat(jdbc.queryForObject("SELECT impact_requirements FROM change_request WHERE id = ?", Integer.class, cr.getId())).isEqualTo(3);
    }

    @Test
    void VYB0907_AC7_applyingTheEditLeavesTheDownstreamLinkSuspect() {
        Requirement child = newRequirement(p, author);
        TraceLink link = trace.createLink(TraceObjectType.REQUIREMENT, approved.getId(), TraceObjectType.REQUIREMENT,
            child.getId(), TraceLinkType.DERIVES, author);
        ChangeRequest cr = raise(approved);
        changeRequests.decide(cr.getId(), true, admin);

        applyEdit(cr, approved, "A different obligation entirely.");

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM finding WHERE rule_key = 'suspect' AND object_type = 'TRACE_LINK'
              AND object_id = ? AND state = 'OPEN'""", Integer.class, link.getId())).isEqualTo(1);
    }
}
