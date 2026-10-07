package com.vyoog.proposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0938 (F30): the one place an AI proposal is recorded and the one place it is decided.
 *
 * <p>A producer ({@code RequirementController}'s rewrite and test-case suggestions, {@code BriefElaborationDrafter})
 * calls {@link #record} with what the AI proposed; nothing is applied. A person then calls {@link #decide}: accepting
 * applies the change through the ordinary service for that kind, so every rule and audit event of a hand-made change
 * applies (an approved requirement still needs a change request; a stale revision is still refused), and rejecting
 * records why. A proposal is decided once.
 */
@Service
public class AiProposalService {

    private static final int MAX_TEXT = 20_000;
    private static final int MAX_REASON = 2_000;

    public enum Decision { ACCEPT, REJECT }

    /**
     * @param requirementRevision the revision the AI was shown
     * @param currentRevision     the requirement's revision now
     * @param stale               the requirement has changed since, so accepting is refused
     * @param acceptedPayload     what was applied when the person edited it first; null otherwise
     */
    public record Proposal(UUID id, ProposalKind kind, ProposalState state, UUID requirementId, String requirementKey,
                           String requirementTitle, Integer requirementRevision, Integer currentRevision, boolean stale,
                           JsonNode payload, JsonNode acceptedPayload, String model, UUID proposedBy, String proposedByName,
                           Instant proposedAt, UUID decidedBy, String decidedByName, Instant decidedAt, String decisionReason,
                           String appliedType, UUID appliedId) {}

    public record Filter(ProposalState state, ProposalKind kind, UUID requirementId) {}

    private static final String SELECT = """
        SELECT p.id, p.kind, p.state, p.requirement_id, r.key AS requirement_key, r.title AS requirement_title,
               p.requirement_revision, r.revision AS current_revision, p.payload::text AS payload,
               p.accepted_payload::text AS accepted_payload, p.model, p.proposed_by, pu.display_name AS proposed_by_name,
               p.proposed_at, p.decided_by, du.display_name AS decided_by_name, p.decided_at, p.decision_reason,
               p.applied_type, p.applied_id
        FROM ai_proposal p
        LEFT JOIN requirement r ON r.id = p.requirement_id
        LEFT JOIN app_user pu ON pu.id = p.proposed_by
        LEFT JOIN app_user du ON du.id = p.decided_by
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final RequirementRepository requirements;
    private final RequirementService requirementService;
    private final TestCaseService testCases;

    public AiProposalService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, RequirementRepository requirements,
                             RequirementService requirementService, TestCaseService testCases) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.requirements = requirements;
        this.requirementService = requirementService;
        this.testCases = testCases;
    }

    private final RowMapper<Proposal> mapper = (rs, n) -> {
        Integer proposedRev = (Integer) rs.getObject("requirement_revision");
        Integer currentRev = (Integer) rs.getObject("current_revision");
        return new Proposal(
            rs.getObject("id", UUID.class), ProposalKind.valueOf(rs.getString("kind")), ProposalState.valueOf(rs.getString("state")),
            rs.getObject("requirement_id", UUID.class), rs.getString("requirement_key"), rs.getString("requirement_title"),
            proposedRev, currentRev, proposedRev != null && currentRev != null && !proposedRev.equals(currentRev),
            tree(rs.getString("payload")), tree(rs.getString("accepted_payload")), rs.getString("model"),
            rs.getObject("proposed_by", UUID.class), rs.getString("proposed_by_name"), instant(rs.getTimestamp("proposed_at")),
            rs.getObject("decided_by", UUID.class), rs.getString("decided_by_name"), instant(rs.getTimestamp("decided_at")),
            rs.getString("decision_reason"), rs.getString("applied_type"), rs.getObject("applied_id", UUID.class));
    };

    private JsonNode tree(String text) {
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("A stored proposal could not be read", e);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records what the AI proposed. Applies nothing. A new BRIEF_ELABORATION for a requirement replaces any still-pending
     * one for it (the older is marked SUPERSEDED), so a review list never holds two answers to the same question.
     *
     * @param requirementId null only for a REWRITE drafted before its requirement exists
     */
    @Transactional
    public UUID record(ProposalKind kind, UUID requirementId, ObjectNode payload, String model, UUID proposedBy) {
        Integer revision = null;
        if (requirementId != null) {
            revision = requirements.findById(requirementId).orElseThrow(() -> new NoSuchElementException("No such requirement")).getRevision();
        } else if (kind != ProposalKind.REWRITE) {
            throw new IllegalArgumentException("A " + kind + " proposal needs a requirement");
        }
        if (kind == ProposalKind.BRIEF_ELABORATION) {
            jdbc.update("UPDATE ai_proposal SET state = 'SUPERSEDED', decided_at = now() WHERE kind = 'BRIEF_ELABORATION' AND state = 'PENDING' AND requirement_id = ?",
                requirementId);
        }
        UUID id = jdbc.queryForObject("""
            INSERT INTO ai_proposal (kind, requirement_id, requirement_revision, payload, model, proposed_by)
            VALUES (?, ?, ?, ?::jsonb, ?, ?) RETURNING id
            """, UUID.class, kind.name(), requirementId, revision, payload.toString(), model, proposedBy);
        audit.record(proposedBy, "ai-proposal.proposed", "AI_PROPOSAL", id, null, afterOf(Map.of("kind", kind.name(), "model", model), requirementId));
        return id;
    }

    private static Map<String, Object> afterOf(Map<String, Object> base, UUID requirementId) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        if (requirementId != null) out.put("requirementId", requirementId.toString());
        return out;
    }

    // ------------------------------------------------------------------ reading

    public Proposal get(UUID id) {
        List<Proposal> found = jdbc.query(SELECT + " WHERE p.id = ?", mapper, id);
        if (found.isEmpty()) throw new NoSuchElementException("No such proposal");
        return found.get(0);
    }

    public Page<Proposal> list(Filter filter, Pageable pageable) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (filter.state() != null) { where.add("p.state = ?"); args.add(filter.state().name()); }
        if (filter.kind() != null) { where.add("p.kind = ?"); args.add(filter.kind().name()); }
        if (filter.requirementId() != null) { where.add("p.requirement_id = ?"); args.add(filter.requirementId()); }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        Long total = jdbc.queryForObject("SELECT count(*) FROM ai_proposal p" + clause, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<Proposal> rows = jdbc.query(SELECT + clause + " ORDER BY p.proposed_at DESC, p.id LIMIT ? OFFSET ?", mapper, pageArgs.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** Pending proposals of one kind for these requirements, oldest first: what a review list for a scope shows. */
    public List<Proposal> pending(ProposalKind kind, Collection<UUID> requirementIds) {
        if (requirementIds.isEmpty()) return List.of();
        return jdbc.query(con -> {
            var ps = con.prepareStatement(SELECT + " WHERE p.kind = ? AND p.state = 'PENDING' AND p.requirement_id = ANY(?) ORDER BY p.proposed_at, p.id");
            ps.setString(1, kind.name());
            ps.setArray(2, con.createArrayOf("uuid", requirementIds.toArray()));
            return ps;
        }, mapper);
    }

    /**
     * The elaboration a person accepted for each of these requirements, for the revision each is at now, as the text that
     * would go in a brief (their edit if they made one). A requirement edited since is absent: its elaboration was written
     * for different words.
     */
    public Map<UUID, String> acceptedBriefElaborations(Collection<UUID> requirementIds) {
        if (requirementIds.isEmpty()) return Map.of();
        Map<UUID, String> out = new LinkedHashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                SELECT DISTINCT ON (p.requirement_id) p.requirement_id, COALESCE(p.accepted_payload, p.payload) ->> 'detail' AS detail
                FROM ai_proposal p JOIN requirement r ON r.id = p.requirement_id AND r.revision = p.requirement_revision
                WHERE p.kind = 'BRIEF_ELABORATION' AND p.state = 'ACCEPTED' AND p.requirement_id = ANY(?)
                ORDER BY p.requirement_id, p.decided_at DESC
                """);
            ps.setArray(1, con.createArrayOf("uuid", requirementIds.toArray()));
            return ps;
        }, rs -> {
            String detail = rs.getString("detail");
            if (detail != null && !detail.isBlank()) out.put(rs.getObject("requirement_id", UUID.class), detail);
        });
        return out;
    }

    // ------------------------------------------------------------------ deciding

    /**
     * @param edits  the payload fields changed before accepting; only {@link ProposalKind#editableKeys()}, never on a reject
     * @param reason why, for a rejection (optional); kept either way
     * @throws IllegalStateException the proposal was already decided, or the requirement has changed since it was made
     */
    @Transactional
    public Proposal decide(UUID id, Decision decision, Map<String, String> edits, String reason, UUID actor) {
        Map<String, Object> row = jdbc.queryForMap("SELECT kind, state, requirement_id, requirement_revision, payload::text AS payload FROM ai_proposal WHERE id = ? FOR UPDATE", id);
        ProposalKind kind = ProposalKind.valueOf((String) row.get("kind"));
        ProposalState state = ProposalState.valueOf((String) row.get("state"));
        if (state != ProposalState.PENDING) {
            throw new IllegalStateException("This proposal was already " + state.name().toLowerCase() + ".");
        }
        UUID requirementId = (UUID) row.get("requirement_id");
        Integer proposedRevision = (Integer) row.get("requirement_revision");
        String cleanReason = clean(reason, MAX_REASON, "The reason");
        Map<String, String> cleanEdits = edits == null ? Map.of() : edits;

        if (decision == Decision.REJECT) {
            if (!cleanEdits.isEmpty()) throw new IllegalArgumentException("A rejected proposal has nothing to edit.");
            jdbc.update("UPDATE ai_proposal SET state = 'REJECTED', decided_by = ?, decided_at = now(), decision_reason = ? WHERE id = ?",
                actor, cleanReason, id);
            audit.record(actor, "ai-proposal.rejected", "AI_PROPOSAL", id, Map.of("state", "PENDING"),
                afterOf(withReason(Map.of("state", "REJECTED", "kind", kind.name()), cleanReason), requirementId));
            return get(id);
        }

        ObjectNode payload = (ObjectNode) tree((String) row.get("payload"));
        ObjectNode accepted = payload.deepCopy();
        for (Map.Entry<String, String> e : cleanEdits.entrySet()) {
            if (!kind.editableKeys().contains(e.getKey())) {
                throw new IllegalArgumentException("A " + kind + " proposal can change only: " + String.join(", ", new java.util.TreeSet<>(kind.editableKeys())) + ".");
            }
            accepted.put(e.getKey(), clean(e.getValue(), MAX_TEXT, "The " + e.getKey()));
        }
        boolean edited = !cleanEdits.isEmpty() && !accepted.equals(payload);

        Requirement requirement = null;
        if (requirementId != null) {
            requirement = requirements.findById(requirementId).orElseThrow(() -> new NoSuchElementException("No such requirement"));
            if (requirement.getRevision() != proposedRevision) {
                throw new IllegalStateException("%s has changed since this was proposed (revision %d, now %d). Reject it and ask again."
                    .formatted(requirement.getKey(), proposedRevision, requirement.getRevision()));
            }
        }

        String appliedType = null;
        UUID appliedId = null;
        switch (kind) {
            case REWRITE -> {
                if (requirement != null) {
                    String statement = text(accepted, "statement");
                    requirementService.update(requirement.getId(), requirement.getRevision(), requirement.getTitle(), statement,
                        requirement.getType(), requirement.getPriority(), requirement.getCapabilityId(), actor);
                    appliedType = "REQUIREMENT";
                    appliedId = requirement.getId();
                }
            }
            case TEST_CASE -> {
                TestCase.Category category = TestCase.Category.valueOf(text(accepted, "category"));
                String description = accepted.path("description").asText("").trim();
                TestCase made = testCases.draft(text(accepted, "title"), description.isEmpty() ? null : description, category,
                    requirementId, actor);
                appliedType = "TEST_CASE";
                appliedId = made.getId();
            }
            case BRIEF_ELABORATION -> {
                text(accepted, "detail");
                // only the latest accepted elaboration for a requirement counts
                jdbc.update("UPDATE ai_proposal SET state = 'SUPERSEDED' WHERE kind = 'BRIEF_ELABORATION' AND state = 'ACCEPTED' AND requirement_id = ? AND id <> ?",
                    requirementId, id);
            }
        }

        final String appliedTypeFinal = appliedType;
        final UUID appliedIdFinal = appliedId;
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                UPDATE ai_proposal SET state = 'ACCEPTED', decided_by = ?, decided_at = now(), decision_reason = ?,
                       accepted_payload = ?::jsonb, applied_type = ?, applied_id = ? WHERE id = ?
                """);
            ps.setObject(1, actor);
            ps.setString(2, cleanReason);
            ps.setString(3, edited ? accepted.toString() : null);
            ps.setString(4, appliedTypeFinal);
            ps.setObject(5, appliedIdFinal);
            ps.setObject(6, id);
            return ps;
        });
        Map<String, Object> after = new LinkedHashMap<>(Map.of("state", "ACCEPTED", "kind", kind.name(), "edited", edited));
        if (appliedType != null) { after.put("appliedType", appliedType); after.put("appliedId", appliedId.toString()); }
        audit.record(actor, "ai-proposal.accepted", "AI_PROPOSAL", id, Map.of("state", "PENDING"), afterOf(after, requirementId));
        return get(id);
    }

    private static Map<String, Object> withReason(Map<String, Object> base, String reason) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        if (reason != null) out.put("reason", reason);
        return out;
    }

    private static String text(ObjectNode node, String key) {
        String v = node.path(key).asText("").trim();
        if (v.isEmpty()) throw new IllegalArgumentException("The proposal has no " + key + " to apply.");
        return v;
    }

    private static String clean(String value, int max, String what) {
        if (value == null) return null;
        String v = value.strip();
        if (v.isEmpty()) {
            if (what.equals("The reason")) return null;
            throw new IllegalArgumentException(what + " cannot be empty.");
        }
        if (v.length() > max) throw new IllegalArgumentException(what + " is too long (at most " + max + " characters).");
        return v;
    }
}
