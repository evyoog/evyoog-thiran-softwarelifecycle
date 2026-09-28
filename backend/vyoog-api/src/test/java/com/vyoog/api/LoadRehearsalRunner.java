package com.vyoog.api;

import com.vyoog.detection.DetectionSweepService;
import com.vyoog.detection.FindingReconciler;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementSpecifications;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * VYB-0780/0781: a real measurement against a real 50,000-row dataset in the local
 * Postgres, not an estimate. Deliberately named so neither Surefire's default include
 * pattern ({@code **}{@code /*Test.java}) nor Failsafe's ({@code **}{@code /*IT.java})
 * picks this up automatically — {@code mvn test}/{@code mvn verify} behave exactly as
 * before. Run only by explicit name:
 *
 * <pre>
 * DB_URL=... DB_USER=... DB_PASSWORD=... \
 *   mvn -pl vyoog-api -am test -Dtest=LoadRehearsalRunner -DfailIfNoTests=false
 * </pre>
 *
 * Requires the dataset seeded directly via SQL for this rehearsal (see
 * docs/load-test-rehearsal.md) — 50,000 {@code requirement} rows keyed
 * {@code VY-LOAD-*}. Talks to the real app context and real beans, no mocks: this is
 * the exact {@code RequirementRepository.findAll(spec, pageable)} call
 * {@code RequirementController#list} makes, and the exact
 * {@code DetectionSweepService} the nightly cron/manual-trigger button calls.
 */
@SpringBootTest
class LoadRehearsalRunner {

    @Autowired RequirementRepository requirements;
    @Autowired DetectionSweepService sweep;

    @Test
    void gridQueryAt50k() {
        long total = requirements.count();
        System.out.println("[load] total requirement rows: " + total);

        timed("unfiltered, default sort, page 0 (size 50)", () -> {
            Specification<Requirement> spec = RequirementSpecifications.notDeleted();
            Page<Requirement> page = requirements.findAll(spec, PageRequest.of(0, 50));
            System.out.println("[load]   -> " + page.getTotalElements() + " total, " + page.getNumberOfElements() + " on page");
        });

        timed("status=IN_REVIEW filter, page 0 (size 50)", () -> {
            Specification<Requirement> spec = RequirementSpecifications.notDeleted()
                .and(RequirementSpecifications.hasStatus(com.vyoog.requirements.RequirementStatus.IN_REVIEW));
            Page<Requirement> page = requirements.findAll(spec, PageRequest.of(0, 50));
            System.out.println("[load]   -> " + page.getTotalElements() + " matching");
        });

        timed("sorted by createdAt desc, page 0 (size 50)", () -> {
            Specification<Requirement> spec = RequirementSpecifications.notDeleted();
            Page<Requirement> page = requirements.findAll(
                spec, PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));
            System.out.println("[load]   -> " + page.getNumberOfElements() + " on page");
        });

        timed("sorted by createdAt desc, deep page (page 900, size 50 — row ~45,000)", () -> {
            Specification<Requirement> spec = RequirementSpecifications.notDeleted();
            Page<Requirement> page = requirements.findAll(
                spec, PageRequest.of(900, 50, Sort.by(Sort.Direction.DESC, "createdAt")));
            System.out.println("[load]   -> " + page.getNumberOfElements() + " on page");
        });

        timed("title trigram search ('scenario 42') page 0 (size 50)", () -> {
            Specification<Requirement> spec = RequirementSpecifications.notDeleted()
                .and(RequirementSpecifications.titleContains("scenario 42"));
            Page<Requirement> page = requirements.findAll(spec, PageRequest.of(0, 50));
            System.out.println("[load]   -> " + page.getTotalElements() + " matching");
        });
    }

    @Test
    void detectionSweepAt50k() {
        long total = requirements.count();
        System.out.println("[load] running a real detection sweep across " + total + " requirements...");
        timed("full detection sweep (all enabled detectors)", () -> {
            List<FindingReconciler.ReconcileResult> results = sweep.triggerManualSweep();
            for (FindingReconciler.ReconcileResult r : results) {
                System.out.println("[load]   " + r.ruleKey() + ": unavailable=" + r.unavailable()
                    + " opened=" + r.opened() + " refreshed=" + r.refreshed()
                    + " reopened=" + r.reopened() + " resolved=" + r.resolved());
            }
        });
    }

    private void timed(String label, Runnable work) {
        long start = System.nanoTime();
        work.run();
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[load] " + label + " -> " + ms + "ms");
    }
}
