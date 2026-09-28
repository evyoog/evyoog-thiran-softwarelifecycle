package com.vyoog.release;

import com.vyoog.platform.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0474–0476. {@code release_scope_item} has no surrogate key, so it's read and
 * written as plain SQL here, same as every other join table without one in this
 * codebase.
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releases;
    private final ScopeMovementRepository movements;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReleaseService(ReleaseRepository releases, ScopeMovementRepository movements,
                           JdbcTemplate jdbc, AuditService audit) {
        this.releases = releases;
        this.movements = movements;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Release create(String name) {
        return releases.save(new Release(name));
    }

    public List<Release> list() {
        return releases.findAll();
    }

    /** VYB-0372: the one genuinely plannable date this application has — set explicitly, never inferred. */
    @Transactional
    public Release setTargetDate(UUID releaseId, java.time.Instant targetDate, UUID actor) {
        Release r = releases.findById(releaseId).orElseThrow(NoSuchElementException::new);
        r.setTargetDate(targetDate);
        releases.save(r);
        audit.record(actor, "release.target-date-set", "RELEASE", releaseId, null,
            Map.of("targetDate", String.valueOf(targetDate)));
        return r;
    }

    /** VYB-0474 AC1: a requirement may be committed to one release at a time. */
    @Transactional
    public void commit(UUID releaseId, UUID requirementId, UUID actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Committing to a release needs a reason");
        }
        releases.findById(releaseId).orElseThrow(NoSuchElementException::new);

        List<UUID> existingReleases = jdbc.queryForList(
            "SELECT release_id FROM release_scope_item WHERE requirement_id = ?", UUID.class, requirementId);
        if (!existingReleases.isEmpty() && !existingReleases.contains(releaseId)) {
            throw new IllegalStateException(
                "This requirement is already committed to another release — remove it there first");
        }
        if (existingReleases.contains(releaseId)) {
            return; // already committed here — idempotent, not an error
        }

        jdbc.update("INSERT INTO release_scope_item (release_id, requirement_id) VALUES (?,?)", releaseId, requirementId);
        movements.save(new ScopeMovement(releaseId, requirementId, MovementDirection.IN, reason, actor));
        audit.record(actor, "release.scope_added", "RELEASE", releaseId, null,
            Map.of("requirementId", requirementId.toString(), "reason", reason));
    }

    @Transactional
    public void removeFromScope(UUID releaseId, UUID requirementId, UUID actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Removing from a release's scope needs a reason");
        }
        jdbc.update("DELETE FROM release_scope_item WHERE release_id = ? AND requirement_id = ?", releaseId, requirementId);
        movements.save(new ScopeMovement(releaseId, requirementId, MovementDirection.OUT, reason, actor));
        audit.record(actor, "release.scope_removed", "RELEASE", releaseId, null,
            Map.of("requirementId", requirementId.toString(), "reason", reason));
    }

    public List<UUID> scope(UUID releaseId) {
        return jdbc.queryForList("SELECT requirement_id FROM release_scope_item WHERE release_id = ?", UUID.class, releaseId);
    }

    public List<ScopeMovement> movements(UUID releaseId, java.time.Instant from, java.time.Instant to) {
        return movements.findAllByReleaseIdAndMovedAtBetweenOrderByMovedAtDesc(releaseId, from, to);
    }

    public record Readiness(int committed, int verified, double verifiedRatio, int criticalOpenGaps) {}

    /** VYB-0476: derived, not stored — critical gaps reported separately from the ratio itself (AC2). */
    public Readiness readiness(UUID releaseId) {
        List<UUID> scopeIds = scope(releaseId);
        if (scopeIds.isEmpty()) return new Readiness(0, 0, 0.0, 0);
        String placeholders = String.join(",", scopeIds.stream().map(i -> "?").toList());

        Integer verified = jdbc.queryForObject(("""
            SELECT count(*) FROM requirement_verification_state vs WHERE vs.id IN (%s) AND vs.is_verified
            """).formatted(placeholders), Integer.class, scopeIds.toArray());
        Integer criticalGaps = jdbc.queryForObject(("""
            SELECT count(*) FROM finding f
            WHERE f.state = 'OPEN' AND f.severity = 'crit' AND f.object_type = 'REQUIREMENT' AND f.object_id IN (%s)
            """).formatted(placeholders), Integer.class, scopeIds.toArray());

        int v = verified == null ? 0 : verified;
        return new Readiness(scopeIds.size(), v, (double) v / scopeIds.size(), criticalGaps == null ? 0 : criticalGaps);
    }

    public record BlockedItem(String requirementId, String key, String reason) {}

    /** VYB-0483: unverified, conflicting or unowned — each with its own reason. */
    public List<BlockedItem> blocked(UUID releaseId) {
        List<UUID> scopeIds = scope(releaseId);
        if (scopeIds.isEmpty()) return List.of();
        String placeholders = String.join(",", scopeIds.stream().map(i -> "?").toList());

        return jdbc.query(("""
            SELECT r.id, r.key,
              CASE
                WHEN NOT vs.is_verified THEN 'unverified'
                WHEN EXISTS (SELECT 1 FROM finding f WHERE f.rule_key = 'conflict' AND f.state = 'OPEN'
                             AND f.object_type = 'REQUIREMENT' AND f.object_id = r.id) THEN 'conflicting'
                WHEN r.owner_id IS NULL THEN 'unowned'
                ELSE NULL
              END AS reason
            FROM requirement r
            JOIN requirement_verification_state vs ON vs.id = r.id
            WHERE r.id IN (%1$s)
            """).formatted(placeholders),
            (rs, n) -> new BlockedItem(rs.getString("id"), rs.getString("key"), rs.getString("reason")),
            scopeIds.toArray())
            .stream().filter(b -> b.reason() != null).toList();
    }

    public record NoteItem(String requirementId, String key, String title, String capabilityName) {}
    public record ReleaseNotes(Map<String, List<NoteItem>> approvedByCapability, List<NoteItem> held) {}

    /**
     * VYB-0484: unheld committed requirements are listed separately, never omitted (AC1),
     * grouped by capability (AC2). VYB-0810: release notes used to be generated from
     * requirements that reached VERIFIED; that status is gone, and APPROVED is now the
     * terminal stage a requirement reaches through this pipeline, so that is the basis now.
     */
    public ReleaseNotes releaseNotes(UUID releaseId) {
        List<UUID> scopeIds = scope(releaseId);
        if (scopeIds.isEmpty()) return new ReleaseNotes(Map.of(), List.of());
        String placeholders = String.join(",", scopeIds.stream().map(i -> "?").toList());

        List<Object[]> rows = jdbc.query(("""
            SELECT r.id, r.key, r.title, r.status, coalesce(c.name, '(unplaced)') AS capability_name
            FROM requirement r LEFT JOIN capability c ON c.id = r.capability_id
            WHERE r.id IN (%s) ORDER BY capability_name, r.key
            """).formatted(placeholders),
            (rs, n) -> new Object[]{
                rs.getString("id"), rs.getString("key"), rs.getString("title"),
                rs.getString("status"), rs.getString("capability_name")},
            scopeIds.toArray());

        Map<String, List<NoteItem>> approvedByCapability = new java.util.LinkedHashMap<>();
        List<NoteItem> held = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            NoteItem item = new NoteItem((String) row[0], (String) row[1], (String) row[2], (String) row[4]);
            if ("APPROVED".equals(row[3])) {
                approvedByCapability.computeIfAbsent(item.capabilityName(), k -> new java.util.ArrayList<>()).add(item);
            } else {
                held.add(item);
            }
        }
        return new ReleaseNotes(approvedByCapability, held);
    }
}
