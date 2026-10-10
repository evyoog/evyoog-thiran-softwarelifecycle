package com.vyoog.requirements;

import com.vyoog.brief.BriefStalenessService;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.audit.AuditService;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the policy the {@link Requirement} entity itself does not: whether a save is
 * material enough to bump the revision (VYB-0112/0113), optimistic concurrency
 * (VYB-0120), the submission gate (VYB-0116), and the audit trail (VYB-0125).
 */
@Service
public class RequirementService {

    private static final Logger log = LoggerFactory.getLogger(RequirementService.class);

    private final RequirementRepository requirements;
    private final RequirementRevisionRepository revisions;
    private final AcceptanceCriterionRepository criteria;
    private final RequirementKeyAllocator keys;
    private final AuditService audit;
    private final DetectionSweepService detection;
    private final BriefStalenessService briefStaleness;
    private final QualityScoreService qualityScore;
    private final JdbcTemplate jdbc;
    private final RequirementEnrichmentService enrichment;
    private final RequirementTransitionAuthorizer authorizer;

    public RequirementService(RequirementRepository requirements,
                               RequirementRevisionRepository revisions,
                               AcceptanceCriterionRepository criteria,
                               RequirementKeyAllocator keys,
                               AuditService audit,
                               DetectionSweepService detection,
                               BriefStalenessService briefStaleness,
                               QualityScoreService qualityScore,
                               JdbcTemplate jdbc,
                               RequirementEnrichmentService enrichment,
                               RequirementTransitionAuthorizer authorizer) {
        this.requirements = requirements;
        this.revisions = revisions;
        this.criteria = criteria;
        this.keys = keys;
        this.audit = audit;
        this.detection = detection;
        this.briefStaleness = briefStaleness;
        this.enrichment = enrichment;
        this.qualityScore = qualityScore;
        this.jdbc = jdbc;
        this.authorizer = authorizer;
    }

    /**
     * VYB-0666: soft-deletes a requirement.
     *
     * <p>Soft, not hard: revisions, acceptance criteria, trace links, audit entries and any
     * brief that already quoted this requirement all reference it. A real DELETE would
     * either cascade through that history or leave it dangling, and the register's whole
     * value is that its history stays true. Every read path already filters on
     * {@code deleted_at IS NULL}, so the requirement disappears from the product while the
     * record of it having existed does not.
     *
     * <p>The reason is recorded on the audit event rather than on the row — the row is
     * gone from every view, so a column nobody reads would be a place for the explanation
     * to rot. The audit trail is where "why did VY-1042 vanish" gets answered.
     */
    @Transactional
    public void delete(UUID id, String reason, UUID actor) {
        Requirement requirement = requirements.findById(id).orElseThrow(NoSuchElementException::new);
        if (requirement.isDeleted()) {
            return;
        }
        requirement.markDeleted(actor);
        requirements.save(requirement);
        audit.record(actor, "requirement.deleted", "REQUIREMENT", id, null,
            Map.of("key", requirement.getKey(), "status", requirement.getStatus().name(),
                   "revision", requirement.getRevision(),
                   "reason", reason == null || reason.isBlank() ? "" : reason));
    }

    /** VYB-0203: recomputed on every save, not just at creation, so it never drifts from what's actually there. */
    private boolean hasUpstreamLink(UUID requirementId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM trace_link WHERE to_type = 'REQUIREMENT' AND to_id = ?)",
            Boolean.class, requirementId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * VYB-0161 AC1: a requirement edit re-evaluates that requirement (and, for the
     * "suspect" rule, any link it's the upstream of — {@code scanOne} interprets the
     * same id both ways). Detection is a secondary concern: if it throws, the write
     * that already committed must not appear to have failed.
     */
    /**
     * VYB-0940: runs the work once this transaction has committed (and never if it rolls back). With no transaction
     * synchronisation to hang the hook off (a plain unit test, or a caller outside a transaction) it runs at once.
     */
    private static void afterCommit(Runnable work) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        work.run();
                    }
                });
        } else {
            work.run();
        }
    }

    private void rescan(UUID requirementId) {
        try {
            // The rescan runs raw SQL on the same connection/transaction — without an
            // explicit flush, Hibernate's pending INSERT/UPDATE for this very save()
            // might not have hit the DB yet, and the detector would see stale state.
            requirements.flush();
            detection.rescanObject(requirementId);
        } catch (Exception e) {
            log.warn("[detection] bounded rescan failed for {}: {}", requirementId, e.getMessage());
        }
    }

    @Transactional
    /** D12: creation at product, application or capability level, or nowhere yet. */
    public Requirement create(String title, String statement, String type, String priority,
                               Placement placement, UUID actorId) {
        Requirement r = new Requirement(keys.next(), title, statement, actorId);
        r.initialize(type, priority, placement);
        r.setQualityScore((short) qualityScore.score(statement, 0, false).total());
        requirements.save(r);

        revisions.save(new RequirementRevision(
            r.getId(), r.getRevision(), r.getStatement(), r.getTitle(),
            r.getType(), r.getStatus().name(), r.getPriority(), r.getCapabilityId(),
            actorId, null));

        audit.record(actorId, "requirement.created", "REQUIREMENT", r.getId(), null, snapshot(r));

        // VYB-0666: scheduled to run once this transaction actually commits, not called
        // here directly. Detection and embedding are each an AI round-trip; a bulk import
        // calls create() once per row inside one commit, and doing this synchronously
        // meant that commit stayed open for as many sequential AI calls as there were
        // rows — the entire wait for a 39-row import was this, not the database. See
        // RequirementEnrichmentService. Registered after-commit rather than called
        // straight through so the enrichment's own transaction never starts before this
        // one has actually persisted the row it needs to read.
        UUID requirementId = r.getId();
        int revision = r.getRevision();
        afterCommit(() -> enrichment.enrich(requirementId, revision, statement));
        return r;
    }

    @Transactional
    public Requirement update(UUID id, int callerRevision, String title, String statement,
                               String type, String priority, UUID capabilityId, UUID actorId) {
        Requirement r = requirements.findById(id).orElseThrow(NoSuchElementException::new);
        // VYB-0390 AC1: once approved, content only moves through a change request —
        // this is the human-facing path, so this is exactly where that gate belongs.
        // com.vyoog.changerequest.ChangeRequestService calls applyChangeRequestEdit
        // instead, which is identical but for skipping this one check.
        if (r.getStatus() == RequirementStatus.APPROVED) {
            throw new IllegalStateException(
                "%s is approved — raise a change request instead of editing it directly".formatted(r.getKey()));
        }
        return applyEdit(r, callerRevision, title, statement, type, priority, capabilityId, actorId, "requirement.revised");
    }

    /**
     * VYB-0390: the one path allowed to edit an approved requirement — used
     * exclusively by {@code ChangeRequestService} once a change request covering it
     * has actually been approved, never reachable from a controller directly.
     */
    @Transactional
    public Requirement applyChangeRequestEdit(UUID id, int callerRevision, String title, String statement,
                                               String type, String priority, UUID capabilityId, UUID actorId) {
        Requirement r = requirements.findById(id).orElseThrow(NoSuchElementException::new);
        return applyEdit(r, callerRevision, title, statement, type, priority, capabilityId, actorId,
            "requirement.revised_via_change_request");
    }

    private Requirement applyEdit(Requirement r, int callerRevision, String title, String statement,
                                   String type, String priority, UUID capabilityId, UUID actorId, String auditAction) {
        if (r.getRevision() != callerRevision) {
            throw new StaleRevisionException(r.getKey(), callerRevision, r.getRevision());
        }

        boolean material = !Objects.equals(r.getTitle(), title)
            || !Objects.equals(r.getStatement().trim(), statement.trim())
            || !Objects.equals(r.getType(), type)
            || !Objects.equals(r.getPriority(), priority)
            || !Objects.equals(r.getCapabilityId(), capabilityId);

        if (!material) {
            return r; // VYB-0113: nothing material changed — no revision, no audit noise.
        }

        Map<String, Object> before = snapshot(r);
        r.applyRevision(title, statement, type, priority, capabilityId, actorId);
        int criteriaCount = (int) criteria.countByRequirementId(r.getId());
        r.setQualityScore((short) qualityScore.score(statement, criteriaCount, hasUpstreamLink(r.getId())).total());
        requirements.save(r);

        revisions.save(new RequirementRevision(
            r.getId(), r.getRevision(), r.getStatement(), r.getTitle(),
            r.getType(), r.getStatus().name(), r.getPriority(), r.getCapabilityId(),
            actorId, null));

        audit.record(actorId, auditAction, "REQUIREMENT", r.getId(), before, snapshot(r));
        rescan(r.getId());
        // VYB-0456 AC1: within one detection cycle — synchronously, right here, not
        // on the next sweep. A revision bump is exactly the condition that matters.
        briefStaleness.markAffectedBriefsStale(r.getId());
        // VYB-0601 AC1: the statement changed (this is what "material" means for a
        // revision bump), so the embedding this revision needs no longer exists.
        // VYB-0940: made after this transaction commits, not inside it; it is a call to a model provider.
        UUID requirementId = r.getId();
        int revision = r.getRevision();
        String statementNow = r.getStatement();
        afterCommit(() -> enrichment.embed(requirementId, revision, statementNow));
        return r;
    }

    @Transactional
    public Requirement transition(UUID id, int callerRevision, RequirementStatus target,
                                   String reason, UUID actorId) {
        Requirement r = requirements.findById(id).orElseThrow(NoSuchElementException::new);
        if (r.getRevision() != callerRevision) {
            throw new StaleRevisionException(r.getKey(), callerRevision, r.getRevision());
        }
        RequirementStatus from = r.getStatus();

        // VYB-0813: whether this caller may make this move at all is checked before
        // any reason/business-rule guard below — someone lacking permission should be
        // refused for that, not shown details (acceptance criteria, blocking
        // clarifications) about a requirement they cannot act on anyway.
        authorizer.authorize(r, from, target, actorId);

        // VYB-0813: reason is required for exactly two edges — a rejection, and a
        // decision maker sending a reviewed requirement back for revision. Reopening a
        // REJECTED requirement into NEEDS_REVISION does not need a fresh reason: the
        // rejection already recorded one.
        boolean reasonBlank = reason == null || reason.isBlank();
        if (target == RequirementStatus.REJECTED && reasonBlank) {
            throw new IllegalArgumentException("A rejection requires a reason");
        }
        if (from == RequirementStatus.REVIEWED && target == RequirementStatus.NEEDS_REVISION && reasonBlank) {
            throw new IllegalArgumentException("Sending this back for revision requires a reason");
        }
        if (target == RequirementStatus.IN_REVIEW
                && criteria.countByRequirementId(id) == 0
                && reasonBlank) {
            throw new IllegalArgumentException(
                "Submitting with no acceptance criteria requires an explicit override reason");
        }
        // VYB-0802: the same "no open blocking clarification" gate ReviewService#close
        // already applies to an entire review round, scoped here to the one requirement
        // being moved into the manual review gate. Read directly off the clarification
        // table via SQL — same as ReviewService does — rather than through a
        // ClarificationService dependency, so this stays a data read and not a new
        // module-to-module coupling.
        if (target == RequirementStatus.REVIEWED) {
            Boolean blocked = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM clarification
                    WHERE requirement_id = ? AND state = 'OPEN' AND blocks_task = true
                )
                """, Boolean.class, id);
            if (Boolean.TRUE.equals(blocked)) {
                throw new IllegalArgumentException(
                    "Cannot move to Reviewed — an open blocking clarification exists on this requirement");
            }
        }

        Map<String, Object> before = Map.of("status", from.name());
        r.transitionTo(target, actorId, reason);
        requirements.save(r);

        // VYB-0813: previous_status/changed_by/changed_at/reason/revision_count all
        // land on the requirement row itself (Requirement#transitionTo); this audit
        // event is the durable history of every transition, not just the latest one.
        audit.record(actorId, "requirement.transitioned", "REQUIREMENT", r.getId(), before, Map.of(
            "status", r.getStatus().name(),
            "previousStatus", from.name(),
            "reason", reason == null ? "" : reason,
            "changedBy", actorId.toString(),
            "changedAt", r.getChangedAt().toString(),
            "revisionCount", r.getRevisionCount()));
        rescan(r.getId());
        return r;
    }

    private Map<String, Object> snapshot(Requirement r) {
        return Map.of(
            "title", r.getTitle(),
            "statement", r.getStatement(),
            "type", r.getType(),
            "priority", r.getPriority(),
            "status", r.getStatus().name(),
            "revision", r.getRevision());
    }
}
