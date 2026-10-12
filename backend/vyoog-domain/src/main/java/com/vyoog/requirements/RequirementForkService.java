package com.vyoog.requirements;

import com.vyoog.platform.audit.AuditService;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0942 (F18, D35): a new version of an approved requirement.
 *
 * <p>A fork is a <b>new requirement</b>: its own id and key, status DRAFT, copied from the approved one (title, statement, type,
 * priority, rationale, placement and acceptance criteria) and linked to it ({@code forked_from_id}) and to the change request that
 * allowed it ({@code forked_for_change_request_id}). It goes through the ordinary status machine and review. When it is approved,
 * {@link RequirementService#transition} marks the original superseded by it; the original's status stays APPROVED (D17: no new
 * status). Until then the original is untouched and still the approved one.
 *
 * <p>What is copied, and what is not: the criteria, and the <i>upstream</i> links (another requirement that SATISFIES, DERIVES or
 * REFINES the original), so the new version is not an orphan. Tests, design, code and defect links are <b>not</b> copied: they
 * covered the old wording, and the new version has to be covered afresh (the same principle that makes a revision bump demote a
 * verified requirement). They stay on the original.
 *
 * <p>The edit-in-place path under an approved change request ({@link RequirementService#applyChangeRequestEdit}) is unchanged;
 * a fork is the other way to act on an approved change request.
 */
@Service
public class RequirementForkService {

    /** The upstream link types a fork keeps; the others describe coverage of the old wording or a conflict to be judged afresh. */
    static final Set<TraceLinkType> COPIED_LINK_TYPES = Set.of(TraceLinkType.SATISFIES, TraceLinkType.DERIVES, TraceLinkType.REFINES);

    public record VersionRef(UUID id, String key, String title, String status) {}

    /** Where a requirement stands in a chain of versions. {@code forks} are the new versions made from it (at most one is open). */
    public record RequirementVersions(VersionRef forkedFrom, UUID changeRequestId, String changeRequestKey, List<VersionRef> forks, VersionRef supersededBy) {}

    /** One fork made for a change request, with the requirement it was made from. */
    public record ForkedFor(VersionRef fork, VersionRef original) {}

    private final RequirementRepository requirements;
    private final RequirementService service;
    private final AcceptanceCriterionService criteria;
    private final TraceGraphService traceGraph;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public RequirementForkService(RequirementRepository requirements, RequirementService service,
                                  AcceptanceCriterionService criteria, TraceGraphService traceGraph,
                                  AuditService audit, JdbcTemplate jdbc) {
        this.requirements = requirements;
        this.service = service;
        this.criteria = criteria;
        this.traceGraph = traceGraph;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * The caller has already checked that the change request is APPROVED and names {@code originalId} in its scope
     * ({@code ChangeRequestService#assertCanEdit}) and that the person may edit the requirement.
     *
     * @throws IllegalStateException the original is not APPROVED, is already superseded, or already has a new version in progress
     */
    @Transactional
    public Requirement fork(UUID originalId, UUID changeRequestId, UUID actorId) {
        Requirement original = requirements.findById(originalId).filter(r -> !r.isDeleted()).orElseThrow(NoSuchElementException::new);
        if (original.getStatus() != RequirementStatus.APPROVED) {
            throw new IllegalStateException("%s is %s. Only an approved requirement can have a new version forked from it."
                .formatted(original.getKey(), original.getStatus()));
        }
        if (original.getSupersededById() != null) {
            throw new IllegalStateException("%s has already been replaced by %s. Fork that one instead."
                .formatted(original.getKey(), keyOf(original.getSupersededById())));
        }
        List<VersionRef> open = openForksOf(originalId);
        if (!open.isEmpty()) {
            throw new IllegalStateException("%s already has a new version in progress: %s (%s)."
                .formatted(original.getKey(), open.get(0).key(), open.get(0).status()));
        }
        String changeRequestKey = jdbc.queryForObject("SELECT key FROM change_request WHERE id = ?", String.class, changeRequestId);

        Requirement fork = service.create(original.getTitle(), original.getStatement(), original.getType(), original.getPriority(),
            Placement.of(original.getProductId(), original.getApplicationId(), original.getCapabilityId()), actorId);
        fork.setRationale(original.getRationale());
        fork.markForkOf(originalId, changeRequestId);
        try {
            requirements.saveAndFlush(fork);
        } catch (DataIntegrityViolationException e) {
            // two forks of the same requirement at once: the unique index let one through
            throw new IllegalStateException("%s already has a new version in progress.".formatted(original.getKey()));
        }

        for (AcceptanceCriterion c : criteria.list(originalId)) criteria.add(fork.getId(), c.getText());

        int linksCopied = 0;
        for (TraceLink link : traceGraph.linksFor(TraceObjectType.REQUIREMENT, originalId).incoming()) {
            if (link.getFromType() != TraceObjectType.REQUIREMENT || !COPIED_LINK_TYPES.contains(link.getLinkType())) continue;
            if (isSuperseded(link.getFromId())) continue; // a replaced parent is not linked to a new child
            traceGraph.createLink(link.getFromType(), link.getFromId(), TraceObjectType.REQUIREMENT, fork.getId(), link.getLinkType(), actorId);
            linksCopied++;
        }

        audit.record(actorId, "requirement.forked", "REQUIREMENT", fork.getId(), null, Map.of(
            "forkedFromId", originalId.toString(), "forkedFromKey", original.getKey(),
            "changeRequestId", changeRequestId.toString(), "changeRequestKey", changeRequestKey,
            "criteriaCopied", criteria.list(fork.getId()).size(), "upstreamLinksCopied", linksCopied));
        audit.record(actorId, "requirement.fork-opened", "REQUIREMENT", originalId, null, Map.of(
            "forkId", fork.getId().toString(), "forkKey", fork.getKey(), "changeRequestKey", changeRequestKey));
        return fork;
    }

    /** The versions around one requirement: what it was forked from, the new versions made from it, what replaced it. */
    public RequirementVersions versionsOf(UUID requirementId) {
        Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        VersionRef from = r.getForkedFromId() == null ? null : ref(r.getForkedFromId());
        String crKey = r.getForkedForChangeRequestId() == null ? null
            : jdbc.queryForObject("SELECT key FROM change_request WHERE id = ?", String.class, r.getForkedForChangeRequestId());
        List<VersionRef> forks = jdbc.query("""
            SELECT id, key, title, status FROM requirement WHERE forked_from_id = ? AND deleted_at IS NULL ORDER BY created_at DESC
            """, (rs, n) -> new VersionRef(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"), rs.getString("status")),
            requirementId);
        VersionRef newer = r.getSupersededById() == null ? null : ref(r.getSupersededById());
        return new RequirementVersions(from, r.getForkedForChangeRequestId(), crKey, forks, newer);
    }

    /** The forks made for one change request, each with the requirement it was made from. */
    public List<ForkedFor> forksFor(UUID changeRequestId) {
        return jdbc.query("""
            SELECT f.id, f.key, f.title, f.status, o.id AS oid, o.key AS okey, o.title AS otitle, o.status AS ostatus
            FROM requirement f JOIN requirement o ON o.id = f.forked_from_id
            WHERE f.forked_for_change_request_id = ? AND f.deleted_at IS NULL ORDER BY f.created_at
            """, (rs, n) -> new ForkedFor(
                new VersionRef(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"), rs.getString("status")),
                new VersionRef(UUID.fromString(rs.getString("oid")), rs.getString("okey"), rs.getString("otitle"), rs.getString("ostatus"))),
            changeRequestId);
    }

    private List<VersionRef> openForksOf(UUID originalId) {
        return jdbc.query("""
            SELECT id, key, title, status FROM requirement
            WHERE forked_from_id = ? AND deleted_at IS NULL AND status <> 'REJECTED'
            """, (rs, n) -> new VersionRef(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"), rs.getString("status")),
            originalId);
    }

    private VersionRef ref(UUID id) {
        return jdbc.query("SELECT id, key, title, status FROM requirement WHERE id = ?",
            rs -> rs.next() ? new VersionRef(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"), rs.getString("status")) : null, id);
    }

    private String keyOf(UUID id) {
        VersionRef r = ref(id);
        return r == null ? id.toString() : r.key();
    }

    private boolean isSuperseded(UUID id) {
        Boolean b = jdbc.queryForObject("SELECT superseded_by_id IS NOT NULL FROM requirement WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(b);
    }
}
