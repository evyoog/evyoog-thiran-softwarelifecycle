package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.FindingReconciler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0960 (D48): writes that re-check what they changed no longer deadlock on the findings they share. The load rehearsal at 60
 * concurrent people found the database stopping writes with "deadlock detected": each write checks the requirement it changed, many
 * writes share findings (a pair of requirements has one finding between them), and two writes took the locks on the same findings in
 * different orders. {@code FindingReconciler.reconcileOne} now takes every lock it needs first, in one fixed order, and inserts new
 * findings in that order too. Against a real PostgreSQL, with many threads, each in its own transaction, each handing the same shared
 * findings over in a different (shuffled) order.
 */
class ConcurrentFindingReconcileIT extends IntegrationTestBase {

    @Autowired FindingReconciler reconciler;
    @Autowired PlatformTransactionManager transactions;

    private static final int THREADS = 8;
    private static final int ROUNDS = 40;
    private static final int SHARED = 10;

    private Candidate candidate(UUID object, String discriminator) {
        return new Candidate("dup", "REQUIREMENT", object, discriminator, 1, "ai", "Possible duplicate", "detail", "suggestion", 0.99, "test", null);
    }

    @Test
    void VYB0960_AC1_manyWritesHandingOverTheSameSharedFindingsInDifferentOrdersNeverDeadlockAndNoFindingIsLostOrDoubled() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<UUID> sharedObjects = new ArrayList<>();
        for (int i = 0; i < SHARED; i++) sharedObjects.add(UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            UUID owner = UUID.randomUUID();
            owners.add(owner);
            int seed = t;
            futures.add(pool.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactions);
                Random random = new Random(seed);
                List<String> failures = new ArrayList<>();
                go.await();
                for (int round = 0; round < ROUNDS; round++) {
                    List<Candidate> mine = new ArrayList<>();
                    for (int s = 0; s < SHARED; s++) mine.add(candidate(sharedObjects.get(s), "shared-" + run + "-" + s));
                    mine.add(candidate(owner, "own-" + run + "-" + round));
                    Collections.shuffle(mine, random); // each write names the same shared findings in its own order
                    try {
                        tx.executeWithoutResult(status -> reconciler.reconcileOne("dup", owner, mine));
                    } catch (RuntimeException e) {
                        failures.add(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
                    }
                }
                return failures;
            }));
        }
        go.countDown();
        List<String> failures = new ArrayList<>();
        for (Future<List<String>> f : futures) failures.addAll(f.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).as("no write was stopped by the database").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding WHERE fingerprint LIKE ?", Integer.class, "dup|REQUIREMENT|%|shared-" + run + "-%"))
            .as("each shared finding exists once").isEqualTo(SHARED);
        for (UUID owner : owners) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM finding WHERE object_id = ? AND state = 'OPEN'", Integer.class, owner))
                .as("a write's own latest finding is open, and the ones it superseded were resolved").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM finding WHERE object_id = ?", Integer.class, owner)).isEqualTo(ROUNDS);
        }
    }

    @Test
    void VYB0960_AC2_aFindingAboutAPairIsFoundAndResolvedFromEitherSideOfThePair() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID lower = a.compareTo(b) < 0 ? a : b, higher = lower.equals(a) ? b : a;
        String disc = lower + "|" + higher;
        Candidate pair = new Candidate("dup", "REQUIREMENT", lower, disc, 1, "ai", "Possible duplicate", "d", "s", 0.99, "test", higher);

        reconciler.reconcileOne("dup", higher, List.of(pair)); // opened from the OTHER side

        assertThat(jdbc.queryForObject("SELECT related_object_id FROM finding WHERE fingerprint = ?", UUID.class, pair.fingerprint())).isEqualTo(higher);
        assertThat(jdbc.queryForObject("SELECT state FROM finding WHERE fingerprint = ?", String.class, pair.fingerprint())).isEqualTo("OPEN");

        var result = reconciler.reconcileOne("dup", higher, List.of()); // the higher one no longer matches anything

        assertThat(result.resolved()).as("re-checking the higher side saw the finding, which is about the lower").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM finding WHERE fingerprint = ?", String.class, pair.fingerprint())).isEqualTo("RESOLVED");
    }
}
