package com.vyoog.clause;

import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0947 (F41, D38): the clause register and its control mapping.
 *
 * <p>A clause is mapped to the requirements that satisfy it by the {@code SATISFIES} trace link the unmapped-clause rule
 * ({@code UnmappedControlClauseDetector}) already reads; nothing new is stored for the mapping. A clause's tests are the tests of
 * those requirements (the {@code TEST VERIFIES REQUIREMENT} links), shown on the clause rather than linked to it a second way.
 *
 * <p>Coverage is derived on every read, never stored:
 * <ul>
 *   <li>{@code UNMAPPED}: no requirement counts toward it;</li>
 *   <li>{@code MAPPED}: at least one counts, and not every one has passing evidence;</li>
 *   <li>{@code EVIDENCED}: at least one counts and every one that counts is APPROVED with a passing test at its current revision
 *       (the same {@code requirement_verification_state} predicate quality reporting uses; it never writes a requirement status).</li>
 * </ul>
 * A requirement counts unless it is deleted, rejected, or replaced by a newer version (D35): such a requirement no longer
 * satisfies anything, so the clause is not covered by it.
 */
@Service
public class ClauseService {

    public static final int MAX_STANDARD = 100;
    public static final int MAX_SECTION = 100;
    public static final int MAX_TEXT = 4000;

    public record RegisterRow(UUID id, String standard, String section, String text, int mappedRequirements, int evidencedRequirements,
                              String coverage) {}

    public record MappedTest(UUID id, String key, String title, String result) {}

    public record MappedRequirement(UUID id, String key, String title, String status, boolean counts, boolean evidenced,
                                    boolean staleEvidence, String replacedByKey, List<MappedTest> tests) {}

    public record Mapping(UUID id, String standard, String section, String text, String coverage, List<MappedRequirement> requirements) {}

    private final ClauseRepository clauses;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TraceGraphService trace;
    private final DetectionSweepService detection;

    public ClauseService(ClauseRepository clauses, JdbcTemplate jdbc, AuditService audit, TraceGraphService trace,
                         DetectionSweepService detection) {
        this.clauses = clauses;
        this.jdbc = jdbc;
        this.audit = audit;
        this.trace = trace;
        this.detection = detection;
    }

    static String coverageOf(int mapped, int evidenced) {
        return mapped == 0 ? "UNMAPPED" : evidenced == mapped ? "EVIDENCED" : "MAPPED";
    }

    // ------------------------------------------------------------------ reading

    private static final String COUNTS = "r.deleted_at IS NULL AND r.superseded_by_id IS NULL AND r.status <> 'REJECTED'";

    /** Every clause with how many requirements count toward it and how many of those have passing evidence. */
    public List<RegisterRow> register() {
        return jdbc.query("""
            SELECT c.id, c.standard, c.section, c.text,
                   count(r.id) AS mapped,
                   count(r.id) FILTER (WHERE r.status = 'APPROVED' AND vs.is_verified) AS evidenced
            FROM clause c
            LEFT JOIN trace_link tl ON tl.to_type = 'CLAUSE' AND tl.to_id = c.id AND tl.from_type = 'REQUIREMENT' AND tl.link_type = 'SATISFIES'
            LEFT JOIN requirement r ON r.id = tl.from_id AND %s
            LEFT JOIN requirement_verification_state vs ON vs.id = r.id
            GROUP BY c.id, c.standard, c.section, c.text
            ORDER BY lower(c.standard), c.section NULLS LAST, c.id
            """.formatted(COUNTS),
            (rs, n) -> new RegisterRow(UUID.fromString(rs.getString("id")), rs.getString("standard"), rs.getString("section"),
                rs.getString("text"), rs.getInt("mapped"), rs.getInt("evidenced"), coverageOf(rs.getInt("mapped"), rs.getInt("evidenced"))));
    }

    /** One clause with the requirements mapped to it, each with its status, its evidence and its tests. */
    public Mapping mappingOf(UUID clauseId) {
        Clause clause = clauses.findById(clauseId).orElseThrow(() -> new NoSuchElementException("No such clause"));
        List<MappedRequirement> requirements = jdbc.query("""
            SELECT r.id, r.key, r.title, r.status, vs.is_verified, vs.has_stale_evidence, n.key AS replaced_by_key,
                   (%s) AS counts
            FROM trace_link tl
            JOIN requirement r ON r.id = tl.from_id AND r.deleted_at IS NULL
            JOIN requirement_verification_state vs ON vs.id = r.id
            LEFT JOIN requirement n ON n.id = r.superseded_by_id
            WHERE tl.to_type = 'CLAUSE' AND tl.to_id = ? AND tl.from_type = 'REQUIREMENT' AND tl.link_type = 'SATISFIES'
            ORDER BY r.key
            """.formatted(COUNTS.replace("r.deleted_at IS NULL AND ", "")),
            (rs, n) -> new MappedRequirement(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("status"), rs.getBoolean("counts"),
                rs.getBoolean("counts") && "APPROVED".equals(rs.getString("status")) && rs.getBoolean("is_verified"),
                rs.getBoolean("has_stale_evidence"), rs.getString("replaced_by_key"), List.of()),
            clauseId);
        List<MappedRequirement> withTests = requirements.stream().map(r -> new MappedRequirement(r.id(), r.key(), r.title(), r.status(),
            r.counts(), r.evidenced(), r.staleEvidence(), r.replacedByKey(), testsOf(r.id()))).toList();
        int mapped = (int) withTests.stream().filter(MappedRequirement::counts).count();
        int evidenced = (int) withTests.stream().filter(MappedRequirement::evidenced).count();
        return new Mapping(clause.getId(), clause.getStandard(), clause.getSection(), clause.getText(), coverageOf(mapped, evidenced), withTests);
    }

    /** A requirement's tests, each with its latest result at the requirement's current revision (null: not run at this revision). */
    private List<MappedTest> testsOf(UUID requirementId) {
        return jdbc.query("""
            SELECT t.id, t.key, t.title,
                   (SELECT v.result FROM verification v JOIN requirement r ON r.id = v.requirement_id
                     WHERE v.requirement_id = ? AND v.test_case_id = t.id AND v.requirement_revision = r.revision
                     ORDER BY v.verified_at DESC LIMIT 1) AS result
            FROM trace_link tl JOIN test_case t ON t.id = tl.from_id
            WHERE tl.from_type = 'TEST' AND tl.to_type = 'REQUIREMENT' AND tl.to_id = ? AND tl.link_type = 'VERIFIES'
            ORDER BY t.key
            """, (rs, n) -> new MappedTest(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("result")), requirementId, requirementId);
    }

    // ------------------------------------------------------------------ the register

    private static String required(String value, String what, int max) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("A clause needs " + what);
        if (v.length() > max) throw new IllegalArgumentException("A clause's " + what + " is at most " + max + " characters");
        return v;
    }

    private static String optional(String value, String what, int max) {
        String v = value == null ? "" : value.strip();
        if (v.length() > max) throw new IllegalArgumentException("A clause's " + what + " is at most " + max + " characters");
        return v.isEmpty() ? null : v;
    }

    /** Two clauses of one standard may not carry the same section: the register would show the same control twice. */
    private void requireUnique(String standard, String section, UUID except) {
        if (section == null) return;
        Boolean taken = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM clause WHERE lower(standard) = lower(?) AND lower(section) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid))
            """, Boolean.class, standard, section, except, except);
        if (Boolean.TRUE.equals(taken)) {
            throw new IllegalStateException("%s %s is already in the register".formatted(standard, section));
        }
    }

    @Transactional
    public Clause create(String standard, String section, String text, UUID actor) {
        String s = required(standard, "a standard", MAX_STANDARD);
        String sec = optional(section, "section", MAX_SECTION);
        String t = required(text, "its text", MAX_TEXT);
        requireUnique(s, sec, null);
        Clause saved = clauses.save(new Clause(s, sec, t));
        audit.record(actor, "clause.created", "CLAUSE", saved.getId(), null,
            Map.of("standard", s, "section", sec == null ? "" : sec));
        detection.rescanObject(saved.getId());
        return saved;
    }

    @Transactional
    public Clause update(UUID id, String standard, String section, String text, UUID actor) {
        Clause clause = clauses.findById(id).orElseThrow(() -> new NoSuchElementException("No such clause"));
        String s = required(standard, "a standard", MAX_STANDARD);
        String sec = optional(section, "section", MAX_SECTION);
        String t = required(text, "its text", MAX_TEXT);
        requireUnique(s, sec, id);
        Map<String, Object> before = Map.of("standard", clause.getStandard(), "section", clause.getSection() == null ? "" : clause.getSection(),
            "text", clause.getText());
        clause.edit(s, sec, t);
        clauses.save(clause);
        audit.record(actor, "clause.updated", "CLAUSE", id, before,
            Map.of("standard", s, "section", sec == null ? "" : sec, "text", t));
        detection.rescanObject(id);
        return clause;
    }

    /**
     * A clause that any requirement (or anything else) is still linked to is not deleted: unmap it first, so a clause cannot vanish
     * from under a mapping a person made. Findings still open against the deleted clause are resolved.
     */
    @Transactional
    public void delete(UUID id, UUID actor) {
        Clause clause = clauses.findById(id).orElseThrow(() -> new NoSuchElementException("No such clause"));
        Integer links = jdbc.queryForObject("""
            SELECT count(*) FROM trace_link WHERE (to_type = 'CLAUSE' AND to_id = ?) OR (from_type = 'CLAUSE' AND from_id = ?)
            """, Integer.class, id, id);
        if (links != null && links > 0) {
            throw new IllegalStateException("%s %s is still linked to %d item%s: remove those links first"
                .formatted(clause.getStandard(), clause.getSection() == null ? "" : clause.getSection(), links, links == 1 ? "" : "s"));
        }
        jdbc.update("UPDATE finding SET state = 'RESOLVED', resolved_at = now() WHERE object_type = 'CLAUSE' AND object_id = ? AND state = 'OPEN'", id);
        clauses.delete(clause);
        audit.record(actor, "clause.deleted", "CLAUSE", id,
            Map.of("standard", clause.getStandard(), "section", clause.getSection() == null ? "" : clause.getSection(), "text", clause.getText()),
            Map.of());
    }

    // ------------------------------------------------------------------ the mapping

    /** Maps a requirement to the clause it satisfies. Already mapped is not an error. A rejected requirement satisfies nothing. */
    @Transactional
    public void map(UUID clauseId, UUID requirementId, UUID actor) {
        clauses.findById(clauseId).orElseThrow(() -> new NoSuchElementException("No such clause"));
        String[] req = jdbc.query("SELECT key, status FROM requirement WHERE id = ? AND deleted_at IS NULL",
            rs -> rs.next() ? new String[] {rs.getString("key"), rs.getString("status")} : null, requirementId);
        if (req == null) throw new NoSuchElementException("No such requirement");
        if (existingLink(clauseId, requirementId) != null) return;
        if ("REJECTED".equals(req[1])) throw new IllegalStateException(req[0] + " is rejected, so it cannot satisfy a clause");
        trace.createLink(TraceObjectType.REQUIREMENT, requirementId, TraceObjectType.CLAUSE, clauseId, TraceLinkType.SATISFIES, actor);
        audit.record(actor, "clause.requirement_mapped", "CLAUSE", clauseId, null,
            Map.of("requirementId", requirementId.toString(), "requirementKey", req[0]));
    }

    @Transactional
    public void unmap(UUID clauseId, UUID requirementId, UUID actor) {
        UUID link = existingLink(clauseId, requirementId);
        if (link == null) throw new NoSuchElementException("That requirement is not mapped to this clause");
        trace.deleteLink(link);
        audit.record(actor, "clause.requirement_unmapped", "CLAUSE", clauseId, null, Map.of("requirementId", requirementId.toString()));
    }

    private UUID existingLink(UUID clauseId, UUID requirementId) {
        return jdbc.query("""
            SELECT id FROM trace_link WHERE from_type = 'REQUIREMENT' AND from_id = ? AND to_type = 'CLAUSE' AND to_id = ? AND link_type = 'SATISFIES'
            """, rs -> rs.next() ? UUID.fromString(rs.getString("id")) : null, requirementId, clauseId);
    }
}
