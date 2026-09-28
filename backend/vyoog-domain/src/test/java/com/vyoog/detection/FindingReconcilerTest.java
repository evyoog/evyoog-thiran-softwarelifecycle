package com.vyoog.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class FindingReconcilerTest {

    @Mock FindingRepository findings;
    @Mock GapRuleService gapRules;
    @Mock EntityManager entityManager;
    @Mock JdbcTemplate jdbc;
    FindingReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new FindingReconciler(findings, gapRules, entityManager, jdbc);
    }

    private Candidate candidate(UUID objectId, int revision) {
        return new Candidate("orphan", "REQUIREMENT", objectId, null, revision, "crit", "t", "d", "s");
    }

    /**
     * VYB-0781: {@link FindingReconciler#reconcile} now pages its resolve-scope
     * ({@link FindingRepository#findAllByRuleKey(String, Pageable)}) instead of
     * loading the whole rule at once — a single, already-exhausted page is enough
     * for every test here since none exercises more than {@code BATCH_SIZE} findings.
     */
    private Page<Finding> onePage(List<Finding> content) {
        return new PageImpl<>(content, PageRequest.of(0, 200), content.size());
    }

    @Test
    void aNewCandidateOpensAFinding() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id, 1);
        when(findings.findByFingerprint(c.fingerprint())).thenReturn(Optional.empty());
        when(findings.findAllByRuleKey(eq("orphan"), any(Pageable.class))).thenReturn(onePage(List.of()));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var result = reconciler.reconcile("orphan", List.of(c));

        verify(jdbc).update(anyString(), any(Object[].class));
        assertThat(result.opened()).isEqualTo(1);
        assertThat(result.resolved()).isZero();
    }

    /**
     * A concurrent writer inserted the same fingerprint between this candidate's own
     * findByFingerprint() check and its insert attempt — insertIfAbsent's
     * ON CONFLICT DO NOTHING reports 0 rows, and the reconciler must fold this
     * candidate into that row (a second findByFingerprint lookup) instead of losing it.
     */
    @Test
    void losingTheInsertRaceStillFoldsIntoTheWinnersRow() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id, 1);
        Finding wonByConcurrentWriter = new Finding(c);
        when(findings.findByFingerprint(c.fingerprint()))
            .thenReturn(Optional.empty(), Optional.of(wonByConcurrentWriter));
        when(findings.findAllByRuleKey(eq("orphan"), any(Pageable.class)))
            .thenReturn(onePage(List.of(wonByConcurrentWriter)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        var result = reconciler.reconcile("orphan", List.of(c));

        assertThat(result.opened()).isZero();
        assertThat(result.refreshed()).isEqualTo(1);
        verify(findings).save(wonByConcurrentWriter);
    }

    @Test
    void anOpenFindingSeenAgainIsRefreshedNotDuplicated() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id, 1);
        Finding existing = new Finding(c);
        when(findings.findByFingerprint(c.fingerprint())).thenReturn(Optional.of(existing));
        when(findings.findAllByRuleKey(eq("orphan"), any(Pageable.class))).thenReturn(onePage(List.of(existing)));

        var result = reconciler.reconcile("orphan", List.of(c));

        assertThat(result.opened()).isZero();
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(existing.getState()).isEqualTo(FindingState.OPEN);
    }

    @Test
    void aFindingNotSeenThisRunIsResolved() {
        UUID id = UUID.randomUUID();
        Finding stale = new Finding(candidate(id, 1));
        when(findings.findAllByRuleKey(eq("orphan"), any(Pageable.class))).thenReturn(onePage(List.of(stale)));

        var result = reconciler.reconcile("orphan", List.of()); // nothing raised this run

        assertThat(result.resolved()).isEqualTo(1);
        assertThat(stale.getState()).isEqualTo(FindingState.RESOLVED);
    }

    @Test
    void dismissedAtTheSameRevisionIsNotResurrected() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id, 1);
        Finding dismissed = new Finding(c);
        dismissed.dismiss("Known and accepted for now", UUID.randomUUID());
        when(findings.findByFingerprint(c.fingerprint())).thenReturn(Optional.of(dismissed));
        when(findings.findAllByRuleKey(eq("orphan"), any(Pageable.class))).thenReturn(onePage(List.of(dismissed)));

        reconciler.reconcile("orphan", List.of(c)); // same revision as when dismissed

        assertThat(dismissed.getState()).isEqualTo(FindingState.DISMISSED);
    }

    @Test
    void dismissedThenRevisionBumpReopensIt() {
        UUID id = UUID.randomUUID();
        Candidate atRevision1 = candidate(id, 1);
        Finding dismissed = new Finding(atRevision1);
        dismissed.dismiss("Known and accepted for now", UUID.randomUUID());
        Candidate atRevision2 = candidate(id, 2); // the requirement moved on

        when(findings.findByFingerprint(atRevision2.fingerprint())).thenReturn(Optional.of(dismissed));
        when(findings.findAllByRuleKey(eq("orphan"), any(Pageable.class))).thenReturn(onePage(List.of(dismissed)));

        var result = reconciler.reconcile("orphan", List.of(atRevision2));

        assertThat(dismissed.getState()).isEqualTo(FindingState.OPEN);
        assertThat(result.reopened()).isEqualTo(1);
    }

    @Test
    void aResolvedFindingThatRecursIsReopened() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id, 1);
        Finding resolved = new Finding(c);
        resolved.resolve();
        when(findings.findByFingerprint(c.fingerprint())).thenReturn(Optional.of(resolved));
        when(findings.findAllByRuleKey(eq("orphan"), any(Pageable.class))).thenReturn(onePage(List.of(resolved)));

        reconciler.reconcile("orphan", List.of(c));

        assertThat(resolved.getState()).isEqualTo(FindingState.OPEN);
    }
}
