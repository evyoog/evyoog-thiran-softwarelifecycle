package com.vyoog.detection;

import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only thing that writes to {@code finding}. Detectors only produce
 * {@link Candidate}s (VYB-0150); this reconciles a rule's fresh candidate set against
 * whatever already exists, by fingerprint (VYB-0151).
 */
@Service
public class FindingReconciler {

    /**
     * VYB-0781: how many {@code save()}s/loop-iterations run between a
     * {@code flush()+clear()}. Small enough that a rule with tens of thousands of open
     * findings (a full-rule {@link #reconcile}, or a detector that shortlists a large
     * candidate set in one call) doesn't grow one Hibernate session unbounded; large
     * enough that this isn't a flush on every single row.
     */
    private static final int BATCH_SIZE = 200;

    private final FindingRepository findings;
    private final GapRuleService gapRules;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public FindingReconciler(FindingRepository findings, GapRuleService gapRules, EntityManager entityManager,
                              JdbcTemplate jdbc) {
        this.findings = findings;
        this.gapRules = gapRules;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    /**
     * VYB-0781: the full-rule path. A rule can accumulate far more findings than a
     * single object's own ({@link #reconcileOne}), so the "gone away" resolve-scope
     * is paged through rather than loaded as one {@code List} up front, with a
     * flush/clear between pages.
     *
     * @return counts by outcome, for VYB-0162 AC2 ("counts per rule are emitted for trending").
     */
    @Transactional
    public ReconcileResult reconcile(String ruleKey, List<Candidate> candidates) {
        List<Candidate> aboveThreshold = inFingerprintOrder(aboveThreshold(ruleKey, candidates));
        Set<String> seenFingerprints = new HashSet<>();
        int[] counts = new int[3]; // opened, refreshed, reopened
        processCandidates(aboveThreshold, seenFingerprints, counts);

        int resolved = 0;
        var page = findings.findAllByRuleKey(ruleKey, PageRequest.of(0, BATCH_SIZE));
        while (true) {
            resolved += resolvePass(page.getContent(), seenFingerprints);
            entityManager.flush();
            entityManager.clear();
            if (!page.hasNext()) break;
            page = findings.findAllByRuleKey(ruleKey, page.nextPageable());
        }

        return new ReconcileResult(ruleKey, counts[0], counts[1], counts[2], resolved, false);
    }

    /**
     * VYB-0161: the bounded, triggered-on-change counterpart. The "anything not seen
     * this run has gone away" step (VYB-0152) is scoped to just this object's own
     * existing findings for the rule, and those about a pair that includes it
     * ({@code related_object_id}, VYB-0960), not the whole rule's findings table: or a bounded
     * rescan of one object would incorrectly resolve every *other* object's open findings for
     * that rule simply because they weren't in this tiny candidate list. That scope is
     * inherently small, so unlike {@link #reconcile} its fingerprints are read as a single list.
     *
     * <p>VYB-0960: <b>fingerprints are handled one at a time in ascending order, each row locked ({@code FOR UPDATE}) at its turn.</b>
     * A finding is shared between the writes that touch the same requirements (a pair of requirements has one finding between
     * them), and two writes that took the locks on shared findings in different orders deadlocked: the load rehearsal at 60
     * concurrent people had the database stop writes with "deadlock detected", surfacing as a 500. With every write taking its locks
     * in the same global order, none can wait on another in a circle, including for a finding another write creates in the meantime
     * (it is locked, or inserted, when its turn comes, never before a larger one already held). The same order is used for inserts.
     */
    @Transactional
    public ReconcileResult reconcileOne(String ruleKey, UUID objectId, List<Candidate> candidates) {
        java.util.Map<String, Candidate> wanted = new java.util.TreeMap<>();
        for (Candidate c : aboveThreshold(ruleKey, candidates)) wanted.put(c.fingerprint(), c);
        java.util.TreeSet<String> turn = new java.util.TreeSet<>(wanted.keySet());
        turn.addAll(jdbc.queryForList(
            "SELECT fingerprint FROM finding WHERE rule_key = ? AND (object_id = ? OR related_object_id = ?)",
            String.class, ruleKey, objectId, objectId));

        int[] counts = new int[3]; // opened, refreshed, reopened
        int resolved = 0;
        for (String fingerprint : turn) {
            Candidate c = wanted.get(fingerprint);
            var existing = findings.lockByFingerprint(fingerprint);
            if (c == null) {
                // in scope but not seen this time: it has gone away
                if (existing.isPresent() && existing.get().getState() != FindingState.RESOLVED) {
                    existing.get().resolve();
                    findings.save(existing.get());
                    resolved++;
                }
            } else if (existing.isPresent()) {
                applyToExisting(existing.get(), c, counts);
            } else if (insertIfAbsent(c) == 1) {
                counts[0]++; // opened
            } else {
                // another write inserted it between our look and our insert; wait for it, then fold into its row
                applyToExisting(findings.lockByFingerprint(fingerprint).orElseThrow(), c, counts);
            }
        }
        return new ReconcileResult(ruleKey, counts[0], counts[1], counts[2], resolved, false);
    }

    /**
     * VYB-0615 AC1: a candidate below its rule's configured confidence threshold is
     * suppressed entirely — treated exactly as if the detector hadn't raised it,
     * which is what lets a below-threshold finding that was previously open
     * auto-resolve through the ordinary "not seen this run" path below rather than
     * needing a second mechanism.
     */
    private List<Candidate> aboveThreshold(String ruleKey, List<Candidate> candidates) {
        java.math.BigDecimal threshold = gapRules.getThreshold(ruleKey);
        if (threshold == null) return candidates;
        return candidates.stream()
            .filter(c -> c.confidence() == null || java.math.BigDecimal.valueOf(c.confidence()).compareTo(threshold) >= 0)
            .toList();
    }

    private static List<Candidate> inFingerprintOrder(List<Candidate> candidates) {
        return candidates.stream().sorted(java.util.Comparator.comparing(Candidate::fingerprint)).toList();
    }

    private void processCandidates(List<Candidate> candidates, Set<String> seenFingerprints, int[] counts) {
        int processed = 0;
        for (Candidate c : candidates) {
            seenFingerprints.add(c.fingerprint());
            var existing = findings.findByFingerprint(c.fingerprint());
            if (existing.isEmpty()) {
                // A plain check-then-insert here races against a concurrent reconcile of
                // the very same object/rule — e.g. RequirementService.create()'s async
                // post-commit enrichment overlapping an immediate RequirementService.update()
                // triggering its own synchronous rescan. Both sides can see "doesn't exist
                // yet" from this same SELECT and both attempt the INSERT; whichever loses
                // hits finding's UNIQUE(fingerprint) constraint (confirmed in production —
                // the resulting DataIntegrityViolationException surfaces from the
                // transaction's flush/commit, not from either side's own save() call, so no
                // try/catch wrapped around a save() here could ever have caught it). An
                // atomic upsert closes the race at the database level instead: Postgres
                // itself serializes two concurrent INSERTs against the same fingerprint —
                // the second blocks until the first commits or rolls back, rather than both
                // independently deciding to insert.
                if (insertIfAbsent(c) == 1) {
                    counts[0]++; // opened
                } else {
                    // Lost the race (or the fingerprint simply already existed under a
                    // read Hibernate's session hadn't yet seen) — someone's row is there
                    // now; fold this candidate into it exactly like the existing branch.
                    applyToExisting(findings.findByFingerprint(c.fingerprint()).orElseThrow(), c, counts);
                }
            } else {
                applyToExisting(existing.get(), c, counts);
            }
            if (++processed % BATCH_SIZE == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
    }

    /** @return rows inserted — 1 on success, 0 if a concurrent writer already holds this fingerprint. */
    private int insertIfAbsent(Candidate c) {
        return jdbc.update("""
            INSERT INTO finding
                (rule_key, fingerprint, object_type, object_id, related_object_id, severity, title, detail, suggestion,
                 object_revision, confidence, model)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (fingerprint) DO NOTHING
            """,
            c.ruleKey(), c.fingerprint(), c.objectType(), c.objectId(), c.relatedObjectId(), c.severity(), c.title(),
            c.detail(), c.suggestion(), c.objectRevision(), c.confidence(), c.model());
    }

    private void applyToExisting(Finding finding, Candidate c, int[] counts) {
        switch (finding.getState()) {
            case OPEN, ACCEPTED -> { finding.refresh(c); counts[1]++; } // refreshed
            case RESOLVED -> { finding.reopen(c); counts[2]++; } // reopened
            case DISMISSED -> {
                // VYB-0151 AC2 / VYB-0154: stays dismissed unless the underlying
                // object's revision has actually moved since the dismissal.
                if (!Objects.equals(finding.getObjectRevision(), c.objectRevision())) {
                    finding.reopen(c);
                    counts[2]++; // reopened
                }
                // else: leave it alone entirely, including content — a human
                // already looked at this; don't rewrite what they dismissed.
            }
        }
        findings.save(finding);
    }

    /** VYB-0152: anything in scope not seen in this run has gone away. */
    private int resolvePass(List<Finding> scope, Set<String> seenFingerprints) {
        int resolved = 0;
        for (Finding f : scope) {
            if (f.getState() != FindingState.RESOLVED && !seenFingerprints.contains(f.getFingerprint())) {
                f.resolve();
                findings.save(f);
                resolved++;
            }
        }
        return resolved;
    }

    /**
     * @param unavailable VYB-0603 AC2: true means the detector itself couldn't run
     *     this cycle (its provider was unreachable/unconfigured) — every other field
     *     is 0 in that case, and it means "unknown", not "nothing found". Only
     *     {@link DetectionSweepService} ever constructs one of these with it true;
     *     an actual reconciliation always produces {@code false}.
     */
    public record ReconcileResult(
        String ruleKey, int opened, int refreshed, int reopened, int resolved, boolean unavailable) {

        public static ReconcileResult unavailable(String ruleKey) {
            return new ReconcileResult(ruleKey, 0, 0, 0, 0, true);
        }
    }
}
