package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.detection.DetectionSweepService;
import com.vyoog.detection.GapRuleService;
import com.vyoog.detection.IncrementalSweep;
import com.vyoog.detection.detectors.ConflictingRequirementsDetector;
import com.vyoog.detection.detectors.DuplicateAcrossApplicationsDetector;
import com.vyoog.requirements.Requirement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0960 (F43, D48): the nightly sweep of the rules that compare requirements with one another checks only what changed, from where
 * it stopped. Against a real PostgreSQL. The database is shared with other tests, so each test looks only at its own requirements and
 * at the sweep's position relative to them.
 */
class IncrementalSweepIT extends IntegrationTestBase {

    @Autowired DetectionSweepService sweep;
    @Autowired IncrementalSweep incremental;
    @Autowired DuplicateAcrossApplicationsDetector dup;
    @Autowired ConflictingRequirementsDetector conflict;
    @Autowired GapRuleService gapRules;

    private String vector(double... leading) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) b.append(i == 0 ? "" : ",").append(i < leading.length ? leading[i] : 0.0);
        return b.append(']').toString();
    }

    private void embed(Requirement r, String vector) {
        jdbc.update("""
            INSERT INTO requirement_embedding (requirement_id, revision, model, embedding, embedded_at) VALUES (?, 1, 'local-hashing-v1', ?::vector, clock_timestamp())
            ON CONFLICT (requirement_id) DO UPDATE SET embedding = EXCLUDED.embedding, embedded_at = clock_timestamp()
            """, r.getId(), vector);
    }

    /**
     * A new requirement is embedded a moment after it is saved, on another thread, with the real (hashing) embedding of its text, and
     * that would overwrite a vector set here. So wait until it has been, then set the vector the test wants.
     */
    private void embedAfterEnrichment(Requirement r, String vector) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && jdbc.queryForObject("SELECT count(*) FROM requirement_embedding WHERE requirement_id = ?", Integer.class, r.getId()) == 0) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        embed(r, vector);
    }

    private boolean waiting(Requirement r) {
        return incremental.changedAfter(incremental.position("dup"), 100_000).stream().anyMatch(c -> c.requirementId().equals(r.getId()));
    }

    /**
     * Which of two ids is the lower, the way PostgreSQL orders uuids (unsigned bytes, which is the order of their lowercase text). Not
     * {@code UUID.compareTo}, which compares signed longs and so disagrees with the database for ids whose first bit is set.
     */
    private static boolean lowerFirst(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) < 0;
    }

    private String stateOf(Requirement a, Requirement b) {
        UUID lower = lowerFirst(a.getId(), b.getId()) ? a.getId() : b.getId();
        UUID higher = lower.equals(a.getId()) ? b.getId() : a.getId();
        List<String> rows = jdbc.queryForList("SELECT state FROM finding WHERE rule_key = 'dup' AND object_id = ? AND related_object_id = ?", String.class, lower, higher);
        return rows.isEmpty() ? "NONE" : rows.get(0);
    }

    private record Trio(Requirement a, Requirement b, Requirement unrelated) {}

    private Trio trio() {
        UUID author = newUser("author");
        Portfolio one = newPortfolio(), two = newPortfolio();
        Requirement a = newRequirement(one, author), b = newRequirement(two, author), u = newRequirement(two, author);
        embedAfterEnrichment(a, vector(1, 0)); embedAfterEnrichment(b, vector(1, 0.01)); embedAfterEnrichment(u, vector(0, 1));
        return new Trio(a, b, u);
    }

    @Test
    void VYB0960_AC3_aSweepChecksWhatChangedFindsThePairAndLeavesNothingWaiting() {
        Trio t = trio();
        assertThat(waiting(t.a())).as("a requirement embedded after the last pass is waiting").isTrue();

        sweep.nightlySweep();

        assertThat(stateOf(t.a(), t.b())).as("the pair in different applications is found").isEqualTo("OPEN");
        assertThat(stateOf(t.a(), t.unrelated())).isEqualTo("NONE");
        assertThat(waiting(t.a()) || waiting(t.b()) || waiting(t.unrelated())).as("all three were checked and none waits").isFalse();
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM detection_sweep_state WHERE rule_key = 'dup'", Boolean.class)).isTrue();
    }

    @Test
    void VYB0960_AC4_aSweepWithNothingNewChecksNoneOfThemAndAFreshEmbeddingIsTheOnlyThingChecked() {
        Trio t = trio();
        sweep.nightlySweep();
        var again = incremental.run(dup, Instant.now().plusSeconds(600));
        assertThat(again.processed()).as("nothing was embedded since: nothing to check").isLessThanOrEqualTo(2); // other tests may embed concurrently

        Requirement c = newRequirement(newPortfolio(), newUser("author"));
        embedAfterEnrichment(c, vector(1, 0.02));
        assertThat(waiting(c)).isTrue();
        assertThat(waiting(t.a())).as("the ones already checked are not waiting again").isFalse();

        var outcome = incremental.run(dup, Instant.now().plusSeconds(600));

        assertThat(outcome.processed()).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(3);
        assertThat(waiting(c)).isFalse();
        assertThat(stateOf(t.a(), c)).as("the new one pairs with the earlier ones").isEqualTo("OPEN");
        assertThat(stateOf(t.b(), c)).isEqualTo("OPEN");
    }

    @Test
    void VYB0960_AC5_aPairThatStoppedBeingSimilarIsResolvedWhicheverSideChanged() {
        Trio t = trio();
        sweep.nightlySweep();
        assertThat(stateOf(t.a(), t.b())).isEqualTo("OPEN");
        Requirement higher = lowerFirst(t.a().getId(), t.b().getId()) ? t.b() : t.a(); // the finding is about the lower id; the higher is its related one

        embed(higher, vector(0, 0, 1)); // the HIGHER one changes to something unrelated: a re-check of it alone must see the pair
        var outcome = incremental.run(dup, Instant.now().plusSeconds(600));

        assertThat(outcome.processed()).isGreaterThanOrEqualTo(1);
        assertThat(stateOf(t.a(), t.b())).isEqualTo("RESOLVED");
    }

    @Test
    void VYB0960_AC6_aPassOutOfTimeChecksNothingMovesNothingAndTheNextPassCarriesOn() {
        Trio t = trio();
        sweep.nightlySweep();
        Requirement c = newRequirement(newPortfolio(), newUser("author"));
        embedAfterEnrichment(c, vector(1, 0.03));
        var before = incremental.position("dup");

        var outOfTime = incremental.run(dup, Instant.now().minusSeconds(1));

        assertThat(outOfTime.processed()).isZero();
        assertThat(outOfTime.stoppedEarly()).isTrue();
        assertThat(incremental.position("dup")).as("the position did not move").isEqualTo(before);
        assertThat(waiting(c)).isTrue();

        var carryOn = incremental.run(dup, Instant.now().plusSeconds(600));

        assertThat(carryOn.stoppedEarly()).isFalse();
        assertThat(waiting(c)).isFalse();
        assertThat(stateOf(t.a(), c)).isEqualTo("OPEN");
    }

    @Test
    void VYB0960_AC7_changingTheRulesThresholdChecksEverythingAgainUnderTheNewOne() {
        Trio t = trio();
        sweep.nightlySweep();
        BigDecimal original = gapRules.getThreshold("dup");
        try {
            gapRules.setThreshold("dup", new BigDecimal("0.999999"));

            var outcome = incremental.run(dup, Instant.now().plusSeconds(600));

            assertThat(outcome.reset()).as("the settings changed, so the position was reset").isTrue();
            assertThat(outcome.processed()).as("everything embedded was checked again").isGreaterThanOrEqualTo(3);
            assertThat(stateOf(t.a(), t.b())).as("under the stricter threshold the pair is no longer similar enough").isEqualTo("RESOLVED");
        } finally {
            gapRules.setThreshold("dup", original);
            incremental.run(dup, Instant.now().plusSeconds(600)); // back to the original settings, and checked under them
        }
        assertThat(stateOf(t.a(), t.b())).isEqualTo("OPEN");
    }

    @Test
    void VYB0960_AC8_aRuleThatCannotRunMovesNoPosition() {
        Trio t = trio();
        jdbc.update("DELETE FROM detection_sweep_state WHERE rule_key = 'conflict'");
        var before = incremental.position("conflict");

        sweep.nightlySweep(); // AI is off in the tests, so the contradiction check is not configured

        assertThat(incremental.position("conflict")).as("what accumulated while it was off is checked when it comes back").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_sweep_state WHERE rule_key = 'conflict'", Integer.class))
            .as("it left no trace: not even a record that it started").isZero();
        assertThat(conflict.sweepsChangedOnly()).isTrue();
        assertThat(dup.sweepsChangedOnly()).isTrue();
        assertThat(stateOf(t.a(), t.b())).as("the duplicate rule still ran").isEqualTo("OPEN");
    }

    @Test
    void VYB0960_AC9_deletingARequirementResolvesItsPairFindingsAtOnceFromEitherSide() {
        Trio t = trio();
        sweep.nightlySweep();
        assertThat(stateOf(t.a(), t.b())).isEqualTo("OPEN");
        UUID admin = newAdministrator();

        requirementService.delete(t.a().getId(), "no longer wanted", admin);

        assertThat(stateOf(t.a(), t.b())).as("the write's own check saw the pair from the requirement that was deleted").isEqualTo("RESOLVED");
    }
}
