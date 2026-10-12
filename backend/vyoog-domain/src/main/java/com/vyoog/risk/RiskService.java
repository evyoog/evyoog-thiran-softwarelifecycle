package com.vyoog.risk;

import com.vyoog.platform.audit.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0949 (F41, D40): the risk register. A risk has a title, a likelihood and an impact (1 to 5, see {@link RiskScale}), an owner, a
 * mitigation and a status, and links to the requirements it touches and the releases it threatens. Every change is audited.
 *
 * <p>Rules: a risk is never deleted (it is closed, and stays); a closed risk is not edited or relinked until it is reopened;
 * moving a risk to Accepted or Closed needs a reason, kept on the risk and on the audit log; a requirement that a newer version has
 * replaced (D35) cannot be linked (link the new one).
 */
@Service
public class RiskService {

    public static final int MAX_TITLE = 200;
    public static final int MAX_TEXT = 4000;
    public static final int MAX_REASON = 1000;
    public static final Set<String> STATUSES = Set.of("OPEN", "MITIGATING", "ACCEPTED", "CLOSED");

    public record RiskRow(UUID id, String key, String title, int likelihood, int impact, int rating, String band, String status,
                          UUID ownerId, String ownerName, int requirements, int releases, Instant createdAt, Instant updatedAt, Instant closedAt) {}

    public record LinkedRequirement(UUID id, String key, String title, String status) {}
    public record LinkedRelease(UUID id, String name, String state) {}

    public record RiskDetail(RiskRow row, String description, String mitigation, String statusReason, String createdByName,
                             List<LinkedRequirement> requirementLinks, List<LinkedRelease> releaseLinks) {}

    /** What a person gives when adding or editing a risk. */
    public record Fields(String title, String description, int likelihood, int impact, UUID ownerId, String mitigation) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public RiskService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    private static Instant at(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static final String ROW_SELECT = """
        SELECT r.id, r.key, r.title, r.likelihood, r.impact, r.status, r.owner_id, o.display_name AS owner_name,
               (SELECT count(*) FROM risk_requirement rr JOIN requirement q ON q.id = rr.requirement_id AND q.deleted_at IS NULL
                 WHERE rr.risk_id = r.id) AS requirements,
               (SELECT count(*) FROM risk_release rl WHERE rl.risk_id = r.id) AS releases,
               r.created_at, r.updated_at, r.closed_at
        FROM risk r LEFT JOIN app_user o ON o.id = r.owner_id
        """;

    private static RiskRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        int l = rs.getInt("likelihood"), i = rs.getInt("impact"), rating = RiskScale.rating(l, i);
        String owner = rs.getString("owner_id");
        return new RiskRow(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"), l, i, rating, RiskScale.band(rating),
            rs.getString("status"), owner == null ? null : UUID.fromString(owner), rs.getString("owner_name"), rs.getInt("requirements"),
            rs.getInt("releases"), at(rs, "created_at"), at(rs, "updated_at"), at(rs, "closed_at"));
    }

    /** Every risk, highest rating first, open ones before closed. */
    public List<RiskRow> list() {
        return jdbc.query(ROW_SELECT + " ORDER BY (r.status = 'CLOSED'), r.likelihood * r.impact DESC, r.key", (rs, n) -> row(rs));
    }

    public RiskDetail detail(UUID id) {
        RiskRow row = jdbc.query(ROW_SELECT + " WHERE r.id = ?", rs -> rs.next() ? row(rs) : null, id);
        if (row == null) throw new NoSuchElementException("No such risk");
        Map<String, String> text = jdbc.query("""
            SELECT r.description, r.mitigation, r.status_reason, c.display_name AS created_by FROM risk r LEFT JOIN app_user c ON c.id = r.created_by WHERE r.id = ?
            """, rs -> {
                rs.next();
                Map<String, String> m = new LinkedHashMap<>();
                m.put("description", rs.getString("description"));
                m.put("mitigation", rs.getString("mitigation"));
                m.put("statusReason", rs.getString("status_reason"));
                m.put("createdBy", rs.getString("created_by"));
                return m;
            }, id);
        List<LinkedRequirement> requirements = jdbc.query("""
            SELECT q.id, q.key, q.title, q.status FROM risk_requirement rr JOIN requirement q ON q.id = rr.requirement_id AND q.deleted_at IS NULL
            WHERE rr.risk_id = ? ORDER BY q.key
            """, (rs, n) -> new LinkedRequirement(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"), rs.getString("status")), id);
        List<LinkedRelease> releases = jdbc.query("""
            SELECT rl.id, rl.name, rl.state FROM risk_release x JOIN release rl ON rl.id = x.release_id WHERE x.risk_id = ? ORDER BY rl.name
            """, (rs, n) -> new LinkedRelease(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("state")), id);
        return new RiskDetail(row, text.get("description"), text.get("mitigation"), text.get("statusReason"), text.get("createdBy"), requirements, releases);
    }

    // ------------------------------------------------------------------ writing

    private static String title(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("A risk needs a title");
        if (v.length() > MAX_TITLE) throw new IllegalArgumentException("A risk's title is at most " + MAX_TITLE + " characters");
        return v;
    }

    private static String text(String value, String what, int max) {
        String v = value == null ? "" : value.strip();
        if (v.length() > max) throw new IllegalArgumentException("A risk's " + what + " is at most " + max + " characters");
        return v.isEmpty() ? null : v;
    }

    private static void scale(int likelihood, int impact) {
        if (!RiskScale.valid(likelihood)) throw new IllegalArgumentException("Likelihood is a whole number from 1 to 5");
        if (!RiskScale.valid(impact)) throw new IllegalArgumentException("Impact is a whole number from 1 to 5");
    }

    private void requireOwner(UUID ownerId) {
        if (ownerId == null) return;
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ?)", Boolean.class, ownerId);
        if (!Boolean.TRUE.equals(exists)) throw new IllegalArgumentException("No such person to own this risk");
    }

    private String status(UUID id) {
        return jdbc.query("SELECT status FROM risk WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
    }

    private String requireOpenForChange(UUID id) {
        String status = status(id);
        if (status == null) throw new NoSuchElementException("No such risk");
        if (status.equals("CLOSED")) throw new IllegalStateException("This risk is closed: reopen it to change it");
        return status;
    }

    @Transactional
    public UUID create(Fields f, UUID actor) {
        String t = title(f.title());
        String description = text(f.description(), "description", MAX_TEXT), mitigation = text(f.mitigation(), "mitigation", MAX_TEXT);
        scale(f.likelihood(), f.impact());
        requireOwner(f.ownerId());
        Long n = jdbc.queryForObject("SELECT nextval('risk_key_seq')", Long.class);
        String key = "RSK-" + n;
        UUID id = jdbc.queryForObject("""
            INSERT INTO risk (key, title, description, likelihood, impact, owner_id, mitigation, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, UUID.class, key, t, description, f.likelihood(), f.impact(), f.ownerId(), mitigation, actor);
        audit.record(actor, "risk.created", "RISK", id, null, Map.of("key", key, "title", t, "likelihood", f.likelihood(), "impact", f.impact(),
            "rating", RiskScale.rating(f.likelihood(), f.impact())));
        return id;
    }

    @Transactional
    public void update(UUID id, Fields f, UUID actor) {
        requireOpenForChange(id);
        String t = title(f.title());
        String description = text(f.description(), "description", MAX_TEXT), mitigation = text(f.mitigation(), "mitigation", MAX_TEXT);
        scale(f.likelihood(), f.impact());
        requireOwner(f.ownerId());
        RiskRow before = detail(id).row();
        jdbc.update("""
            UPDATE risk SET title = ?, description = ?, likelihood = ?, impact = ?, owner_id = ?, mitigation = ?, updated_at = now() WHERE id = ?
            """, t, description, f.likelihood(), f.impact(), f.ownerId(), mitigation, id);
        audit.record(actor, "risk.updated", "RISK", id,
            Map.of("title", before.title(), "likelihood", before.likelihood(), "impact", before.impact(), "rating", before.rating(),
                "owner", before.ownerId() == null ? "" : before.ownerId().toString()),
            Map.of("title", t, "likelihood", f.likelihood(), "impact", f.impact(), "rating", RiskScale.rating(f.likelihood(), f.impact()),
                "owner", f.ownerId() == null ? "" : f.ownerId().toString()));
    }

    /** Moves a risk to another status. Accepted and Closed need a reason; a closed or accepted risk may be reopened. */
    @Transactional
    public void changeStatus(UUID id, String target, String reason, UUID actor) {
        String from = status(id);
        if (from == null) throw new NoSuchElementException("No such risk");
        if (target == null || !STATUSES.contains(target)) throw new IllegalArgumentException("Status is one of OPEN, MITIGATING, ACCEPTED, CLOSED");
        if (from.equals(target)) throw new IllegalStateException("This risk is already " + target);
        String why = reason == null ? "" : reason.strip();
        if (why.length() > MAX_REASON) throw new IllegalArgumentException("A reason is at most " + MAX_REASON + " characters");
        if ((target.equals("ACCEPTED") || target.equals("CLOSED")) && why.isEmpty()) {
            throw new IllegalArgumentException("Say why this risk is " + target.toLowerCase() + ": a reason is required");
        }
        jdbc.update("""
            UPDATE risk SET status = ?, status_reason = ?, closed_at = CASE WHEN ? = 'CLOSED' THEN now() ELSE NULL END, updated_at = now() WHERE id = ?
            """, target, why.isEmpty() ? null : why, target, id);
        audit.record(actor, "risk.status_changed", "RISK", id, Map.of("status", from), Map.of("status", target, "reason", why));
    }

    // ------------------------------------------------------------------ links

    @Transactional
    public void linkRequirement(UUID riskId, UUID requirementId, UUID actor) {
        requireOpenForChange(riskId);
        record Req(String key, boolean replaced) {}
        Req req = jdbc.query("SELECT key, superseded_by_id IS NOT NULL AS replaced FROM requirement WHERE id = ? AND deleted_at IS NULL",
            rs -> rs.next() ? new Req(rs.getString("key"), rs.getBoolean("replaced")) : null, requirementId);
        if (req == null) throw new NoSuchElementException("No such requirement");
        if (req.replaced()) {
            throw new IllegalStateException(req.key() + " has been replaced by a newer version, so it takes no new links. Link the new version instead.");
        }
        int added = jdbc.update("INSERT INTO risk_requirement (risk_id, requirement_id) VALUES (?, ?) ON CONFLICT DO NOTHING", riskId, requirementId);
        if (added > 0) audit.record(actor, "risk.requirement_linked", "RISK", riskId, null, Map.of("requirementId", requirementId.toString(), "requirementKey", req.key()));
    }

    @Transactional
    public void unlinkRequirement(UUID riskId, UUID requirementId, UUID actor) {
        requireOpenForChange(riskId);
        if (jdbc.update("DELETE FROM risk_requirement WHERE risk_id = ? AND requirement_id = ?", riskId, requirementId) == 0) {
            throw new NoSuchElementException("That requirement is not linked to this risk");
        }
        audit.record(actor, "risk.requirement_unlinked", "RISK", riskId, null, Map.of("requirementId", requirementId.toString()));
    }

    @Transactional
    public void linkRelease(UUID riskId, UUID releaseId, UUID actor) {
        requireOpenForChange(riskId);
        String name = jdbc.query("SELECT name FROM release WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, releaseId);
        if (name == null) throw new NoSuchElementException("No such release");
        int added = jdbc.update("INSERT INTO risk_release (risk_id, release_id) VALUES (?, ?) ON CONFLICT DO NOTHING", riskId, releaseId);
        if (added > 0) audit.record(actor, "risk.release_linked", "RISK", riskId, null, Map.of("releaseId", releaseId.toString(), "releaseName", name));
    }

    @Transactional
    public void unlinkRelease(UUID riskId, UUID releaseId, UUID actor) {
        requireOpenForChange(riskId);
        if (jdbc.update("DELETE FROM risk_release WHERE risk_id = ? AND release_id = ?", riskId, releaseId) == 0) {
            throw new NoSuchElementException("That release is not linked to this risk");
        }
        audit.record(actor, "risk.release_unlinked", "RISK", riskId, null, Map.of("releaseId", releaseId.toString()));
    }
}
