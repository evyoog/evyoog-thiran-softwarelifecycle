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

    /** VYB-0930: one release, or {@link NoSuchElementException}. */
    public Release find(UUID releaseId) {
        return releases.findById(releaseId).orElseThrow(() -> new NoSuchElementException("No such release: " + releaseId));
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
        requireScopeEditable(releases.findById(releaseId).orElseThrow(NoSuchElementException::new));

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
        requireScopeEditable(releases.findById(releaseId).orElseThrow(NoSuchElementException::new));
        jdbc.update("DELETE FROM release_scope_item WHERE release_id = ? AND requirement_id = ?", releaseId, requirementId);
        movements.save(new ScopeMovement(releaseId, requirementId, MovementDirection.OUT, reason, actor));
        audit.record(actor, "release.scope_removed", "RELEASE", releaseId, null,
            Map.of("requirementId", requirementId.toString(), "reason", reason));
    }

    /** VYB-0928: a frozen or released release's scope is locked; a frozen one can be reopened to change it. */
    private static void requireScopeEditable(Release release) {
        if (!release.getState().scopeEditable()) {
            throw new IllegalStateException("The scope of a " + release.getState().name().toLowerCase()
                + " release cannot change" + (release.getState() == ReleaseState.FROZEN ? "; reopen it first" : ""));
        }
    }

    public List<UUID> scope(UUID releaseId) {
        return jdbc.queryForList("SELECT requirement_id FROM release_scope_item WHERE release_id = ?", UUID.class, releaseId);
    }

    public record ScopeItem(String requirementId, String key, String title, String status, String capabilityName) {}

    /** VYB-0930: what is committed, with key and title, so a screen shows requirements rather than ids. */
    public List<ScopeItem> scopeItems(UUID releaseId) {
        find(releaseId);
        return jdbc.query("""
            SELECT r.id, r.key, r.title, r.status, coalesce(c.name, '(unplaced)') AS capability_name
              FROM release_scope_item s JOIN requirement r ON r.id = s.requirement_id
              LEFT JOIN capability c ON c.id = r.capability_id
             WHERE s.release_id = ? ORDER BY r.key
            """, (rs, n) -> new ScopeItem(rs.getString("id"), rs.getString("key"), rs.getString("title"),
                rs.getString("status"), rs.getString("capability_name")), releaseId);
    }

    /** {@code committedTo} is the release the requirement is committed to, when it is (this one included). */
    public record Candidate(String requirementId, String key, String title, String status, String committedToId,
                             String committedToName) {}

    /**
     * VYB-0930: requirements a person can pick for this release's scope, searchable by key or title (wildcards in the
     * search are literal), each saying which release it is already committed to, if any, so the picker can show it as
     * taken instead of letting the server refuse it. Deleted requirements are not offered.
     */
    public org.springframework.data.domain.Page<Candidate> candidates(UUID releaseId, String q,
                                                                      org.springframework.data.domain.Pageable pageable) {
        find(releaseId);
        List<Object> args = new java.util.ArrayList<>();
        String filter = "";
        if (q != null && !q.isBlank()) {
            filter = " AND (r.key ILIKE ? OR r.title ILIKE ?)";
            String like = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        String from = """
              FROM requirement r
              LEFT JOIN release_scope_item s ON s.requirement_id = r.id
              LEFT JOIN release rl ON rl.id = s.release_id
             WHERE r.deleted_at IS NULL""" + filter;
        Long total = jdbc.queryForObject("SELECT count(*) " + from, Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<Candidate> rows = jdbc.query("SELECT r.id, r.key, r.title, r.status, rl.id AS release_id, rl.name AS release_name "
            + from + " ORDER BY r.key LIMIT ? OFFSET ?", (rs, n) -> new Candidate(rs.getString("id"), rs.getString("key"),
                rs.getString("title"), rs.getString("status"), rs.getString("release_id"), rs.getString("release_name")),
            pageArgs.toArray());
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public record BulkResult(List<UUID> committed, List<UUID> alreadyCommitted) {}

    private static final int MAX_BULK = 200;

    /**
     * VYB-0930: commits several requirements to the release with one reason, all or nothing. Refused as a whole, with
     * every offending requirement named in one message, if the scope is locked, the reason is missing, a requirement
     * does not exist, or one is committed to another release; one already committed here is skipped and reported, as
     * a single commit is. Each commit leaves its own scope movement and audit event, exactly as {@link #commit} does.
     */
    @Transactional
    public BulkResult commitAll(UUID releaseId, List<UUID> requirementIds, UUID actor, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Committing to a release needs a reason");
        if (requirementIds == null || requirementIds.isEmpty()) throw new IllegalArgumentException("Pick at least one requirement");
        List<UUID> ids = requirementIds.stream().distinct().toList();
        if (ids.size() > MAX_BULK) throw new IllegalArgumentException("Commit at most " + MAX_BULK + " requirements at a time");
        requireScopeEditable(find(releaseId));

        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        java.util.Map<UUID, String> keys = new java.util.HashMap<>();
        jdbc.query(("SELECT id, key FROM requirement WHERE deleted_at IS NULL AND id IN (%s)").formatted(placeholders),
            rs -> { keys.put(UUID.fromString(rs.getString("id")), rs.getString("key")); }, ids.toArray());
        List<UUID> missing = ids.stream().filter(i -> !keys.containsKey(i)).toList();
        if (!missing.isEmpty()) throw new NoSuchElementException(missing.size() + " of the requirements do not exist");

        java.util.Map<UUID, UUID> elsewhere = new java.util.HashMap<>();
        java.util.Set<UUID> here = new java.util.HashSet<>();
        jdbc.query(("SELECT requirement_id, release_id FROM release_scope_item WHERE requirement_id IN (%s)").formatted(placeholders),
            rs -> {
                UUID req = UUID.fromString(rs.getString("requirement_id")), rel = UUID.fromString(rs.getString("release_id"));
                if (rel.equals(releaseId)) here.add(req); else elsewhere.put(req, rel);
            }, ids.toArray());
        if (!elsewhere.isEmpty()) {
            String taken = String.join(", ", elsewhere.keySet().stream().map(keys::get).sorted().toList());
            throw new IllegalStateException(elsewhere.size() + " of the requirements are already committed to another release ("
                + taken + "); remove them there first. Nothing was committed.");
        }

        List<UUID> committed = new java.util.ArrayList<>();
        List<UUID> already = new java.util.ArrayList<>();
        for (UUID id : ids) {
            if (here.contains(id)) { already.add(id); continue; }
            jdbc.update("INSERT INTO release_scope_item (release_id, requirement_id) VALUES (?,?)", releaseId, id);
            movements.save(new ScopeMovement(releaseId, id, MovementDirection.IN, reason, actor));
            audit.record(actor, "release.scope_added", "RELEASE", releaseId, null,
                Map.of("requirementId", id.toString(), "reason", reason));
            committed.add(id);
        }
        return new BulkResult(committed, already);
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
