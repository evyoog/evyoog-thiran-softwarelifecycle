package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.requirements.RequirementStatus;
import com.vyoog.savedview.SavedView;
import com.vyoog.savedview.SavedViewService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * VYB-0911 (F22): a saved view stores the filters of the requirement grid, and the status filter
 * has to accept every status a requirement can be in. The table's CHECK still listed the old
 * five-state set from before V026-V028: it refused REVIEWED and NEEDS_REVISION (so a person could not
 * save a view of requirements awaiting a decision) and still allowed VERIFIED, which is no longer a
 * status (D16).
 */
class SavedViewIT extends IntegrationTestBase {

    @Autowired SavedViewService views;

    private Set<String> checkValues(String table, String column) {
        String def = jdbc.queryForObject("""
            SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
             WHERE c.contype = 'c' AND c.conrelid = ?::regclass AND pg_get_constraintdef(c.oid) LIKE ?
            """, String.class, table, "%(" + column + " = ANY%");
        Set<String> values = new TreeSet<>();
        Matcher m = Pattern.compile("'([A-Z_]+)'::text").matcher(def);
        while (m.find()) values.add(m.group(1));
        return values;
    }

    @Test
    void VYB0911_AC1_aViewCanBeSavedForEveryStatusARequirementCanHave() {
        UUID owner = newUser("sv");
        for (RequirementStatus status : RequirementStatus.values()) {
            SavedView saved = views.save(owner, unique("view-" + status), status.name(), null, null, null, null);
            assertThat(saved.getStatus()).isEqualTo(status.name());
        }
        assertThat(views.mine(owner)).hasSize(RequirementStatus.values().length);
    }

    @Test
    void VYB0911_AC1_theStatusCheckAllowsExactlyTheRequirementStatusesAndNotVerified() {
        Set<String> expected = new TreeSet<>(Arrays.stream(RequirementStatus.values()).map(Enum::name).toList());

        assertThat(checkValues("saved_view", "status")).isEqualTo(expected).doesNotContain("VERIFIED");
        assertThat(checkValues("requirement", "status")).as("the requirement table agrees").isEqualTo(expected);
    }

    @Test
    void VYB0911_AC1_thePriorityAndTypeChecksAgreeWithTheRequirementTable() {
        assertThat(checkValues("saved_view", "priority")).isEqualTo(checkValues("requirement", "priority"));
        assertThat(checkValues("saved_view", "type")).isEqualTo(checkValues("requirement", "type"));
    }

    @Test
    void VYB0911_AC1_aStatusThatIsNoLongerARequirementStatusIsStillRefused() {
        UUID owner = newUser("sv");
        assertThatThrownBy(() -> views.save(owner, unique("old"), "VERIFIED", null, null, null, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void VYB0911_AC1_noSavedViewIsLeftFilteringOnTheRemovedVerifiedStatus() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saved_view WHERE status = 'VERIFIED'", Integer.class)).isZero();
    }
}
