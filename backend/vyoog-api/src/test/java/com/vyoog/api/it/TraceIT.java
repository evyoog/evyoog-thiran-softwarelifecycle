package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** VYB-0907 (F11): the trace graph (links, closure table, traversal, suspect links, coverage) against real SQL. */
class TraceIT extends IntegrationTestBase {

    @Autowired TraceGraphService trace;

    private Portfolio p;
    private UUID author;
    private Requirement a, b, c;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        a = newRequirement(p, author);
        b = newRequirement(p, author);
        c = newRequirement(p, author);
    }

    private TraceLink link(Requirement from, Requirement to) {
        return trace.createLink(TraceObjectType.REQUIREMENT, from.getId(), TraceObjectType.REQUIREMENT, to.getId(),
            TraceLinkType.DERIVES, author);
    }

    private int closureDepth(Requirement from, Requirement to) {
        List<Integer> d = jdbc.queryForList("""
            SELECT depth FROM trace_closure WHERE ancestor_type = 'REQUIREMENT' AND ancestor_id = ?
              AND descendant_type = 'REQUIREMENT' AND descendant_id = ?""", Integer.class, from.getId(), to.getId());
        return d.isEmpty() ? -1 : d.get(0);
    }

    @Test
    void VYB0907_AC1_aNewLinkRecordsTheUpstreamRevisionItWasMadeAgainstAndEntersTheClosure() {
        TraceLink l = link(a, b);
        assertThat(l.getReviewedAtRevision()).isEqualTo(a.getRevision());
        assertThat(closureDepth(a, b)).isEqualTo(1);
    }

    @Test
    void VYB0907_AC1_aDuplicateLinkIsRefusedAndAMissingEndpointIsRefused() {
        link(a, b);
        assertThatThrownBy(() -> link(a, b)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> trace.createLink(TraceObjectType.REQUIREMENT, a.getId(), TraceObjectType.REQUIREMENT,
            UUID.randomUUID(), TraceLinkType.DERIVES, author))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No such REQUIREMENT");
    }

    @Test
    void VYB0907_AC2_theClosureHoldsTransitiveReachAndShrinksWhenALinkIsDeleted() {
        link(a, b);
        TraceLink bc = link(b, c);
        assertThat(closureDepth(a, c)).isEqualTo(2);
        assertThat(trace.checkClosureDrift()).as("stored closure equals a fresh computation").isFalse();

        trace.deleteLink(bc.getId());
        assertThat(closureDepth(a, c)).as("no orphan closure row").isEqualTo(-1);
        assertThat(closureDepth(a, b)).isEqualTo(1);
        assertThat(trace.checkClosureDrift()).isFalse();
    }

    @Test
    void VYB0907_AC3_downstreamAndUpstreamWalksReturnEachNodeWithItsDepth() {
        link(a, b);
        link(b, c);
        var down = trace.downstream(TraceObjectType.REQUIREMENT, a.getId(), 5);
        assertThat(down).extracting(r -> r.id()).containsExactlyInAnyOrder(b.getId(), c.getId());
        assertThat(down).filteredOn(r -> r.id().equals(c.getId())).extracting(r -> r.depth()).containsExactly(2);

        var up = trace.upstream(TraceObjectType.REQUIREMENT, c.getId(), 5);
        assertThat(up).extracting(r -> r.id()).containsExactlyInAnyOrder(b.getId(), a.getId());
    }

    @Test
    void VYB0907_AC3_aWalkStopsAtTheRequestedDepth() {
        link(a, b);
        link(b, c);
        assertThat(trace.downstream(TraceObjectType.REQUIREMENT, a.getId(), 1)).extracting(r -> r.id()).containsExactly(b.getId());
    }

    @Test
    void VYB0907_AC3_aCycleDoesNotMakeTheWalkLoopForeverOrRepeatANode() {
        link(a, b);
        link(b, c);
        link(c, a);
        var down = trace.downstream(TraceObjectType.REQUIREMENT, a.getId(), 10);
        assertThat(down).extracting(r -> r.id()).doesNotHaveDuplicates();
        assertThat(down).extracting(r -> r.id()).contains(b.getId(), c.getId());
        assertThat(trace.checkClosureDrift()).isFalse();
    }

    @Test
    void VYB0907_AC4_editingTheUpstreamRequirementMakesItsLinkSuspectAndReviewingItClearsThat() {
        TraceLink l = link(a, b);
        assertThat(openSuspectFindings(l)).isZero();

        requirementService.update(a.getId(), a.getRevision(), a.getTitle(), "The system shall now behave differently.",
            a.getType(), a.getPriority(), a.getCapabilityId(), author);
        assertThat(openSuspectFindings(l)).as("the upstream moved past the revision the link was reviewed at").isEqualTo(1);

        TraceLink reviewed = trace.reviewLink(l.getId());
        assertThat(reviewed.getReviewedAtRevision()).isEqualTo(2);
        assertThat(openSuspectFindings(l)).isZero();
    }

    @Test
    void VYB0907_AC5_coverageShowsWhichRequirementsHaveAnUpstream() {
        link(a, b);
        var coverage = trace.coverageFor(List.of(a.getId(), b.getId()));
        assertThat(coverage.get(b.getId()).hasUpstream()).isTrue();
        assertThat(coverage.get(a.getId()).hasUpstream()).isFalse();
    }

    private int openSuspectFindings(TraceLink l) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM finding WHERE rule_key = 'suspect' AND object_type = 'TRACE_LINK'
              AND object_id = ? AND state = 'OPEN'""", Integer.class, l.getId());
    }
}
