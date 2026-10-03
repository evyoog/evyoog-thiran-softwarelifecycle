package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.release.Release;
import com.vyoog.release.ReleaseService;
import com.vyoog.requirements.Requirement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** VYB-0907 (F11): releases: scope commitment (one release at a time), movements, readiness, blocked items, notes. */
class ReleaseIT extends IntegrationTestBase {

    @Autowired ReleaseService releases;

    private Portfolio p;
    private UUID author, admin;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
    }

    @Test
    void VYB0907_AC1_committingToAReleaseNeedsAReasonAndIsIdempotent() {
        Release rel = releases.create(unique("R"));
        Requirement r = newRequirement(p, author);

        assertThatThrownBy(() -> releases.commit(rel.getId(), r.getId(), admin, " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");

        releases.commit(rel.getId(), r.getId(), admin, "needed for the pilot");
        releases.commit(rel.getId(), r.getId(), admin, "again");           // not an error, not a second row
        assertThat(releases.scope(rel.getId())).containsExactly(r.getId());
        assertThat(releases.movements(rel.getId(), Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS)))
            .hasSize(1);
        assertThat(auditCount(rel.getId(), "release.scope_added")).isEqualTo(1);
    }

    @Test
    void VYB0907_AC1_aRequirementCanBeInOnlyOneReleaseAtATime() {
        Release first = releases.create(unique("R"));
        Release second = releases.create(unique("R"));
        Requirement r = newRequirement(p, author);
        releases.commit(first.getId(), r.getId(), admin, "first");

        assertThatThrownBy(() -> releases.commit(second.getId(), r.getId(), admin, "second"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("another release");

        releases.removeFromScope(first.getId(), r.getId(), admin, "moving it");
        releases.commit(second.getId(), r.getId(), admin, "now here");
        assertThat(releases.scope(first.getId())).isEmpty();
        assertThat(releases.scope(second.getId())).containsExactly(r.getId());
    }

    @Test
    void VYB0907_AC2_removingFromScopeNeedsAReasonAndLeavesAnOutMovementInTheHistory() {
        Release rel = releases.create(unique("R"));
        Requirement r = newRequirement(p, author);
        releases.commit(rel.getId(), r.getId(), admin, "in");
        assertThatThrownBy(() -> releases.removeFromScope(rel.getId(), r.getId(), admin, ""))
            .isInstanceOf(IllegalArgumentException.class);

        releases.removeFromScope(rel.getId(), r.getId(), admin, "descoped for capacity");
        var movements = releases.movements(rel.getId(), Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(movements).extracting(m -> m.getDirection().name()).containsExactlyInAnyOrder("IN", "OUT");
    }

    @Test
    void VYB0907_AC3_readinessIsDerivedFromTheScopeAndAnUnverifiedRequirementIsReportedBlocked() {
        Release rel = releases.create(unique("R"));
        assertThat(releases.readiness(rel.getId()).committed()).isZero();

        Requirement r = newRequirement(p, author);
        releases.commit(rel.getId(), r.getId(), admin, "in");

        var readiness = releases.readiness(rel.getId());
        assertThat(readiness.committed()).isEqualTo(1);
        assertThat(readiness.verified()).isZero();
        assertThat(readiness.verifiedRatio()).isZero();
        assertThat(releases.blocked(rel.getId())).extracting(ReleaseService.BlockedItem::reason).contains("unverified");
    }

    @Test
    void VYB0907_AC4_releaseNotesSeparateApprovedRequirementsFromHeldOnes() {
        Release rel = releases.create(unique("R"));
        Requirement done = approved(newRequirement(p, author), author, admin);
        Requirement draft = newRequirement(p, author);
        releases.commit(rel.getId(), done.getId(), admin, "ready");
        releases.commit(rel.getId(), draft.getId(), admin, "not ready yet");

        var notes = releases.releaseNotes(rel.getId());
        assertThat(notes.approvedByCapability().values().stream().flatMap(java.util.List::stream))
            .extracting(ReleaseService.NoteItem::key).containsExactly(done.getKey());
        assertThat(notes.held()).extracting(ReleaseService.NoteItem::key).containsExactly(draft.getKey());
    }

    @Test
    void VYB0907_AC5_aTargetDateIsStoredAndAudited() {
        Release rel = releases.create(unique("R"));
        Instant date = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        releases.setTargetDate(rel.getId(), date, admin);
        assertThat(jdbc.queryForObject("SELECT target_date FROM release WHERE id = ?", java.sql.Timestamp.class, rel.getId()).toInstant())
            .isEqualTo(date);
        assertThat(auditCount(rel.getId(), "release.target-date-set")).isEqualTo(1);
    }
}
