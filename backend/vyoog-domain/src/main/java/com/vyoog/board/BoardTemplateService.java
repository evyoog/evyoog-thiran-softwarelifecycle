package com.vyoog.board;

import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.RequirementStatus;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0953 (F42, D42): board templates. The board is the requirement lifecycle as columns, driven by each requirement's status; a
 * template only chooses which columns to show, in what order, and which requirements (a scope, a type, a priority). Moving a card is
 * the ordinary status move with all its rules, done elsewhere; nothing here changes a requirement.
 *
 * <p>A template belongs to a team (its lead or an Administrator edits it); the one team-less template, "Default", is for everyone,
 * is edited by an Administrator only and is never deleted. Anyone signed in may open any template.
 */
@Service
public class BoardTemplateService {

    public static final int MAX_NAME = 100;

    /** The same lists the saved views use; the artifact registry (D25) would replace them. */
    public static final Set<String> TYPES = Set.of("FUNCTIONAL", "NON_FUNCTIONAL", "BUSINESS_RULE", "INTERFACE", "DATA", "REPORT", "SECURITY", "COMPLIANCE");
    public static final Set<String> PRIORITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    public record Template(UUID id, UUID teamId, String teamName, String name, List<String> columns, UUID productId, String productName,
                           UUID applicationId, String applicationName, UUID capabilityId, String capabilityName, String type, String priority,
                           boolean isDefault, Instant updatedAt, UUID scopeProductId, UUID scopeApplicationId) {}

    /** What a person gives when making or changing a template. {@code teamId} is used on create only. */
    public record Fields(UUID teamId, String name, List<String> columns, UUID productId, UUID applicationId, UUID capabilityId, String type, String priority) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public BoardTemplateService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    /** The product and application names and ids are those the scope sits under, so a capability's template names its application and product too. */
    private static final String SELECT = """
        SELECT b.id, b.team_id, t.name AS team_name, b.name, b.columns, b.product_id, p.name AS product_name, b.application_id,
               a.name AS application_name, b.capability_id, c.name AS capability_name, b.type, b.priority, b.updated_at,
               p.id AS scope_product_id, a.id AS scope_application_id
        FROM board_template b
        LEFT JOIN team t ON t.id = b.team_id
        LEFT JOIN capability c ON c.id = b.capability_id
        LEFT JOIN application a ON a.id = COALESCE(b.application_id, c.application_id)
        LEFT JOIN product p ON p.id = COALESCE(b.product_id, a.product_id)
        """;

    private static UUID uuid(String s) { return s == null ? null : UUID.fromString(s); }

    private static Template row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Array cols = rs.getArray("columns");
        List<String> columns = List.of((String[]) cols.getArray());
        String team = rs.getString("team_id");
        return new Template(UUID.fromString(rs.getString("id")), uuid(team), rs.getString("team_name"), rs.getString("name"), columns,
            uuid(rs.getString("product_id")), rs.getString("product_name"), uuid(rs.getString("application_id")), rs.getString("application_name"),
            uuid(rs.getString("capability_id")), rs.getString("capability_name"), rs.getString("type"), rs.getString("priority"), team == null,
            rs.getTimestamp("updated_at").toInstant(), uuid(rs.getString("scope_product_id")), uuid(rs.getString("scope_application_id")));
    }

    /** The default first, then each team's templates by team and name. */
    public List<Template> list() {
        return jdbc.query(SELECT + " ORDER BY (b.team_id IS NOT NULL), lower(t.name), lower(b.name)", (rs, n) -> row(rs));
    }

    public Template get(UUID id) {
        List<Template> found = jdbc.query(SELECT + " WHERE b.id = ?", (rs, n) -> row(rs), id);
        if (found.isEmpty()) throw new NoSuchElementException("No such board template");
        return found.get(0);
    }

    // ------------------------------------------------------------------ writing

    private static String name(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("A template needs a name");
        if (v.length() > MAX_NAME) throw new IllegalArgumentException("A template's name is at most " + MAX_NAME + " characters");
        return v;
    }

    private static List<String> columns(List<String> value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("A template needs at least one column");
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String c : value) {
            RequirementStatus status;
            try {
                status = RequirementStatus.valueOf(c == null ? "" : c.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Not a status: " + c);
            }
            if (!seen.add(status.name())) throw new IllegalArgumentException("A column appears twice: " + status.name());
            out.add(status.name());
        }
        return out;
    }

    private void checkScope(Fields f) {
        int named = (f.productId() != null ? 1 : 0) + (f.applicationId() != null ? 1 : 0) + (f.capabilityId() != null ? 1 : 0);
        if (named > 1) throw new IllegalArgumentException("A template's scope is one of a product, an application or a capability");
        requireExists("product", f.productId(), "product");
        requireExists("application", f.applicationId(), "application");
        requireExists("capability", f.capabilityId(), "capability");
        if (f.type() != null && !f.type().isBlank() && !TYPES.contains(f.type())) throw new IllegalArgumentException("Not a requirement type: " + f.type());
        if (f.priority() != null && !f.priority().isBlank() && !PRIORITIES.contains(f.priority())) throw new IllegalArgumentException("Not a priority: " + f.priority());
    }

    private void requireExists(String table, UUID id, String what) {
        if (id == null) return;
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = ?)", Boolean.class, id);
        if (!Boolean.TRUE.equals(exists)) throw new IllegalArgumentException("No such " + what);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    private void requireNameFree(UUID teamId, String name, UUID except) {
        Boolean taken = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM board_template WHERE team_id IS NOT DISTINCT FROM ?::uuid AND lower(name) = lower(?)
                           AND (?::uuid IS NULL OR id <> ?::uuid))
            """, Boolean.class, teamId, name, except, except);
        if (Boolean.TRUE.equals(taken)) throw new IllegalStateException("There is already a template called \"" + name + "\"" + (teamId == null ? "" : " for this team"));
    }

    @Transactional
    public UUID create(Fields f, UUID actor) {
        if (f.teamId() == null) throw new IllegalArgumentException("A template belongs to a team");
        requireExists("team", f.teamId(), "team");
        String n = name(f.name());
        List<String> cols = columns(f.columns());
        checkScope(f);
        requireNameFree(f.teamId(), n, null);
        UUID id = jdbc.queryForObject("""
            INSERT INTO board_template (team_id, name, columns, product_id, application_id, capability_id, type, priority, created_by)
            VALUES (?, ?, ?::text[], ?, ?, ?, ?, ?, ?) RETURNING id
            """, UUID.class, f.teamId(), n, "{" + String.join(",", cols) + "}", f.productId(), f.applicationId(), f.capabilityId(),
            blankToNull(f.type()), blankToNull(f.priority()), actor);
        audit.record(actor, "board.template_created", "BOARD_TEMPLATE", id, null,
            Map.of("team", f.teamId().toString(), "name", n, "columns", String.join(",", cols)));
        return id;
    }

    @Transactional
    public void update(UUID id, Fields f, UUID actor) {
        Template before = get(id);
        String n = name(f.name());
        List<String> cols = columns(f.columns());
        checkScope(f);
        requireNameFree(before.teamId(), n, id);
        jdbc.update("""
            UPDATE board_template SET name = ?, columns = ?::text[], product_id = ?, application_id = ?, capability_id = ?, type = ?, priority = ?,
                   updated_at = now() WHERE id = ?
            """, n, "{" + String.join(",", cols) + "}", f.productId(), f.applicationId(), f.capabilityId(), blankToNull(f.type()), blankToNull(f.priority()), id);
        audit.record(actor, "board.template_updated", "BOARD_TEMPLATE", id,
            Map.of("name", before.name(), "columns", String.join(",", before.columns())),
            Map.of("name", n, "columns", String.join(",", cols)));
    }

    /** The default is never deleted. */
    @Transactional
    public void delete(UUID id, UUID actor) {
        Template t = get(id);
        if (t.isDefault()) throw new IllegalStateException("The default template cannot be deleted");
        jdbc.update("DELETE FROM board_template WHERE id = ?", id);
        audit.record(actor, "board.template_deleted", "BOARD_TEMPLATE", id,
            Map.of("team", t.teamId().toString(), "name", t.name(), "columns", String.join(",", t.columns())), Map.of());
    }
}
