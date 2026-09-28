package com.vyoog.requirements;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.identity.GrantRequiredException;
import com.vyoog.platform.audit.AuditEvent;
import com.vyoog.platform.audit.AuditEventRepository;
import com.vyoog.platform.audit.AuditService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0121/0122: a bulk change applied row by row, honouring the state machine per
 * row, recorded as one action an administrator can undo as a whole.
 *
 * <p>Deliberately does not touch title/statement/revision — bulk edit is for
 * metadata (status, priority, type, capability, owner), never content. Status
 * changes still go through {@link Requirement#transitionTo}, so an illegal
 * transition skips only that row (AC1) exactly the same way a single transition
 * would refuse it.
 */
@Service
public class BulkEditService {

    private static final String ACTION = "requirement.bulk_edit";

    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;
    private final AuditService audit;
    private final AuditEventRepository auditEvents;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final RequirementTransitionAuthorizer authorizer;

    public BulkEditService(RequirementRepository requirements, AcceptanceCriterionRepository criteria,
                            AuditService audit, AuditEventRepository auditEvents, ObjectMapper json,
                            JdbcTemplate jdbc, RequirementTransitionAuthorizer authorizer) {
        this.requirements = requirements;
        this.criteria = criteria;
        this.audit = audit;
        this.auditEvents = auditEvents;
        this.json = json;
        this.jdbc = jdbc;
        this.authorizer = authorizer;
    }

    /**
     * @param reason the same reason {@link RequirementService#transition} requires for a
     *     rejection, and for submitting a requirement that has no acceptance criteria.
     *     Bulk edit had no such field, which is why it could do both without one.
     */
    public record Changes(RequirementStatus status, String priority, String type,
                           boolean touchCapability, UUID capabilityId, UUID ownerId, String reason) {

        /** Pre-reason callers — every guarded target then refuses, as it should without one. */
        public Changes(RequirementStatus status, String priority, String type,
                       boolean touchCapability, UUID capabilityId, UUID ownerId) {
            this(status, priority, type, touchCapability, capabilityId, ownerId, null);
        }

        boolean hasReason() { return reason != null && !reason.isBlank(); }
    }

    public record RowOutcome(UUID id, boolean applied, String reason) {}
    public record BulkResult(UUID batchId, List<RowOutcome> outcomes) {}

    @Transactional
    public BulkResult apply(List<UUID> ids, Changes changes, UUID actor) {
        List<Map<String, Object>> beforeRows = new ArrayList<>();
        List<Map<String, Object>> afterRows = new ArrayList<>();
        List<RowOutcome> outcomes = new ArrayList<>();

        for (UUID id : ids) {
            var opt = requirements.findById(id);
            if (opt.isEmpty()) {
                outcomes.add(new RowOutcome(id, false, "No such requirement"));
                continue;
            }
            Requirement r = opt.get();
            RequirementStatus from = r.getStatus();
            Map<String, Object> before = snapshot(r);
            try {
                if (changes.status() != null) {
                    // VYB-0813: the same per-edge author/reviewer/decision-maker/admin
                    // check the single-transition endpoint enforces, and in the same
                    // order — whether this caller may make this move at all, before
                    // any reason/business-rule guard reveals details about a
                    // requirement they cannot act on anyway. A bulk edit is not a
                    // second, looser way to move a requirement someone lacks
                    // permission to move one at a time.
                    authorizer.authorize(r, from, changes.status(), actor);
                    refuseIfGuarded(r, changes);
                    r.transitionTo(changes.status(), actor, changes.reason()); // AC1: throws on an illegal move
                }
                r.reassign(changes.priority(), changes.type(), changes.capabilityId(), changes.touchCapability(), actor);
                if (changes.ownerId() != null) {
                    r.setOwnerId(changes.ownerId());
                }
                requirements.save(r);
                beforeRows.add(before);
                afterRows.add(snapshot(r));
                outcomes.add(new RowOutcome(id, true, null));
            } catch (IllegalStateException | GrantRequiredException guardRefused) {
                // AC1: a partial success is not reported as a failure — this row is
                // just skipped, the others still apply.
                outcomes.add(new RowOutcome(id, false, guardRefused.getMessage()));
            }
        }

        UUID batchId = UUID.randomUUID();
        audit.record(actor, ACTION, "BULK", batchId, Map.of("rows", beforeRows), Map.of("rows", afterRows));
        return new BulkResult(batchId, outcomes);
    }

    /** VYB-0122 AC1/AC2: restores every applied row; a row changed by someone else since is skipped, not clobbered. */
    @Transactional
    @SuppressWarnings("unchecked")
    public BulkResult undo(UUID batchId, UUID actor) {
        AuditEvent event = auditEvents.findByObjectIdAndAction(batchId, ACTION)
            .orElseThrow(() -> new NoSuchElementException("No such bulk edit: " + batchId));

        List<Map<String, Object>> beforeRows;
        try {
            Map<String, Object> wrapper = json.readValue(event.getBefore(), Map.class);
            beforeRows = (List<Map<String, Object>>) wrapper.get("rows");
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the original state for this batch");
        }

        List<RowOutcome> outcomes = new ArrayList<>();
        for (Map<String, Object> before : beforeRows) {
            UUID id = UUID.fromString((String) before.get("id"));
            var opt = requirements.findById(id);
            if (opt.isEmpty()) {
                outcomes.add(new RowOutcome(id, false, "No longer exists"));
                continue;
            }
            Requirement r = opt.get();
            try {
                RequirementStatus originalStatus = RequirementStatus.valueOf((String) before.get("status"));
                if (r.getStatus() != originalStatus) {
                    r.transitionTo(originalStatus, actor); // may legitimately fail — see class doc
                }
                String capabilityIdStr = (String) before.get("capabilityId");
                r.reassign((String) before.get("priority"), (String) before.get("type"),
                    capabilityIdStr == null ? null : UUID.fromString(capabilityIdStr), true, actor);
                requirements.save(r);
                outcomes.add(new RowOutcome(id, true, null));
            } catch (IllegalStateException cannotReverse) {
                outcomes.add(new RowOutcome(id, false,
                    "Could not restore the previous status: " + cannotReverse.getMessage()));
            }
        }
        return new BulkResult(batchId, outcomes);
    }

    /**
     * The lifecycle guards {@link RequirementService#transition} enforces, applied here
     * too. Bulk edit called {@link Requirement#transitionTo} straight, so it was a way
     * round every one of them:
     *
     * <ul>
     *   <li><b>REJECTED</b> — a rejection carries a reason. Bulk edit has nowhere to put
     *       one, so it may not reject.
     *   <li><b>NEEDS_REVISION, from REVIEWED</b> — VYB-0813: same reason requirement as
     *       the single-transition endpoint; reopening a REJECTED row into NEEDS_REVISION
     *       needs none, since the rejection already carries one.
     *   <li><b>IN_REVIEW</b> — submitting with no acceptance criteria needs an explicit
     *       override reason, for the same lack of anywhere to record one.
     *   <li><b>REVIEWED</b> — VYB-0810: the same "no open blocking clarification" gate
     *       {@link RequirementService#transition} applies to the manual review gate.
     * </ul>
     *
     * <p>VYB-0810: VERIFIED used to be guarded here too — Principle 3, a predicate only
     * {@code VerificationService} could set. That status no longer exists, so there is
     * nothing left to guard against a bulk edit offering it.
     */
    private void refuseIfGuarded(Requirement r, Changes changes) {
        switch (changes.status()) {
            case REJECTED -> {
                if (!changes.hasReason()) throw new IllegalStateException("A rejection requires a reason");
            }
            case NEEDS_REVISION -> {
                if (r.getStatus() == RequirementStatus.REVIEWED && !changes.hasReason()) {
                    throw new IllegalStateException("Sending this back for revision requires a reason");
                }
            }
            case IN_REVIEW -> {
                // Checked per row, not per batch: a selection can mix requirements that
                // have criteria with ones that do not, and only the latter need the override.
                if (r.getStatus() != RequirementStatus.IN_REVIEW
                        && criteria.countByRequirementId(r.getId()) == 0
                        && !changes.hasReason()) {
                    throw new IllegalStateException(
                        "Submitting with no acceptance criteria requires an explicit override reason");
                }
            }
            case REVIEWED -> {
                Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS(
                        SELECT 1 FROM clarification
                        WHERE requirement_id = ? AND state = 'OPEN' AND blocks_task = true
                    )
                    """, Boolean.class, r.getId());
                if (Boolean.TRUE.equals(blocked)) {
                    throw new IllegalStateException(
                        "Cannot move to Reviewed — an open blocking clarification exists on this requirement");
                }
            }
            default -> { /* DRAFT and APPROVED carry no extra guard */ }
        }
    }

    private Map<String, Object> snapshot(Requirement r) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", r.getId().toString());
        m.put("status", r.getStatus().name());
        m.put("priority", r.getPriority());
        m.put("type", r.getType());
        m.put("capabilityId", r.getCapabilityId() == null ? null : r.getCapabilityId().toString());
        return m;
    }
}
