package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.baseline.Baseline;
import com.vyoog.baseline.BaselineService;
import com.vyoog.release.ReleaseService;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** VYB-0907 (F11): baselines freeze the revisions in scope; later edits never change a frozen baseline; diffs compare two. */
class BaselineIT extends IntegrationTestBase {

    @Autowired BaselineService baselines;
    @Autowired ReleaseService releases;

    private Portfolio p;
    private UUID author, admin, releaseId;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
        releaseId = releases.create(unique("R")).getId();
    }

    private Requirement edit(Requirement r, String statement) {
        return requirementService.update(r.getId(), r.getRevision(), r.getTitle(), statement, r.getType(),
            r.getPriority(), r.getCapabilityId(), author);
    }

    @Test
    void VYB0907_AC1_aBaselineNeedsAtLeastOneRequirement() {
        assertThatThrownBy(() -> baselines.freeze("empty", releaseId, List.of(), admin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0907_AC1_freezingRecordsEachRevisionAtThatMomentAndAnAuditEvent() {
        Requirement a = newRequirement(p, author);
        Requirement b = edit(newRequirement(p, author), "Revised once before the freeze.");     // revision 2
        Baseline base = baselines.freeze(unique("BL"), releaseId, List.of(a.getId(), b.getId()), admin);

        assertThat(baselines.items(base.getId())).extracting(BaselineService.BaselineItemView::revision)
            .containsExactlyInAnyOrder(1, 2);
        assertThat(auditCount(base.getId(), "baseline.frozen")).isEqualTo(1);
    }

    @Test
    void VYB0907_AC2_aFrozenBaselineDoesNotChangeWhenTheRequirementIsEditedLater() {
        Requirement a = newRequirement(p, author);
        Baseline base = baselines.freeze(unique("BL"), releaseId, List.of(a.getId()), admin);
        edit(a, "A later edit, after the freeze.");
        assertThat(baselines.items(base.getId())).extracting(BaselineService.BaselineItemView::revision).containsExactly(1);
    }

    @Test
    void VYB0907_AC3_openGapsAtFreezeTimeAreRecordedOnTheBaseline() {
        Requirement a = newRequirement(p, author);
        int open = jdbc.queryForObject("SELECT count(*) FROM finding WHERE state = 'OPEN' AND object_type = 'REQUIREMENT' AND object_id = ?",
            Integer.class, a.getId());
        Baseline base = baselines.freeze(unique("BL"), releaseId, List.of(a.getId()), admin);
        assertThat(base.getGapsAtFreeze()).isEqualTo(open);
    }

    @Test
    void VYB0907_AC4_aDiffListsAddedRemovedAndChangedSeparatelyWithBothRevisions() {
        Requirement kept = newRequirement(p, author);
        Requirement dropped = newRequirement(p, author);
        Requirement added = newRequirement(p, author);

        Baseline v1 = baselines.freeze(unique("BL"), releaseId, List.of(kept.getId(), dropped.getId()), admin);
        Requirement keptEdited = edit(kept, "Changed between the two baselines.");
        Baseline v2 = baselines.freeze(unique("BL"), releaseId, List.of(keptEdited.getId(), added.getId()), admin);

        var diff = baselines.diff(v1.getId(), v2.getId());
        assertThat(diff.added()).extracting(BaselineService.BaselineItemView::key).containsExactly(added.getKey());
        assertThat(diff.removed()).extracting(BaselineService.BaselineItemView::key).containsExactly(dropped.getKey());
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.changed().get(0).fromRevision()).isEqualTo(1);
        assertThat(diff.changed().get(0).toRevision()).isEqualTo(2);
        assertThat(diff.changed().get(0).key()).isEqualTo(kept.getKey());
    }
}
