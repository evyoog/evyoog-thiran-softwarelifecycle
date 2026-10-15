package com.vyoog.integration.planner;

import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.AcceptanceCriterion;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.requirements.RequirementStatus;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0919 (D24, D51): what a person does with a change the Agile Planner proposed to a requirement it created.
 *
 * <p>Accepting is an edit of the requirement by the person who accepts, under the normal rules (D24 answer 3): the proposal's title,
 * statement and priority replace the requirement's, as a new revision made by that person; the type and the placement are left as they
 * are. Criteria the proposal has that the requirement does not (the same words, exactly) are added at the end; <b>none is ever removed</b>
 * (a criterion may already be linked to tests), so a criterion the Planner dropped stays until a person removes it. An approved
 * requirement changes only through a change request, so accepting one is refused with that said, and the proposal stays pending.
 * Rejecting records the decision and changes nothing. Either way the proposal is kept, with who decided and when.
 */
@Service
public class PlannerProposalService {

    static final int MAX_NOTE = 500;

    /** One proposal as a person sees it. {@code decidedBy} is a display name; nothing here is the Planner's own record. */
    public record PlannerProposalView(UUID id, String state, Instant receivedAt, String sourceFunctionId, String title, String statement,
                               String priority, List<String> criteria, String decidedBy, Instant decidedAt, String decisionNote,
                               Integer appliedRevision) {}

    private final JdbcTemplate jdbc;
    private final RequirementService requirementService;
    private final AcceptanceCriterionService criteria;
    private final RequirementRepository requirements;
    private final AuditService audit;

    public PlannerProposalService(JdbcTemplate jdbc, RequirementService requirementService, AcceptanceCriterionService criteria,
                                  RequirementRepository requirements, AuditService audit) {
        this.jdbc = jdbc;
        this.requirementService = requirementService;
        this.criteria = criteria;
        this.requirements = requirements;
        this.audit = audit;
    }

    private static final String SELECT = """
        SELECT p.id, p.state, p.received_at, l.source_function_id, p.title, p.statement, p.priority, p.criteria, u.display_name,
               p.decided_at, p.decision_note, p.applied_revision
          FROM planner_change_proposal p
          JOIN planner_function_link l ON l.id = p.link_id
          LEFT JOIN app_user u ON u.id = p.decided_by
        """;

    private static final RowMapper<PlannerProposalView> VIEW = (rs, n) -> {
        List<String> texts = new ArrayList<>();
        try {
            Array a = rs.getArray("criteria");
            if (a != null) for (Object o : (Object[]) a.getArray()) texts.add((String) o);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        var decidedAt = rs.getTimestamp("decided_at");
        Integer revision = rs.getObject("applied_revision", Integer.class);
        return new PlannerProposalView(rs.getObject("id", UUID.class), rs.getString("state"), rs.getTimestamp("received_at").toInstant(),
            rs.getString("source_function_id"), rs.getString("title"), rs.getString("statement"), rs.getString("priority"), texts,
            rs.getString("display_name"), decidedAt == null ? null : decidedAt.toInstant(), rs.getString("decision_note"),
            revision);
    };

    /** The proposals made on this requirement, those still waiting first, then the newest. Superseded ones are not shown. */
    public List<PlannerProposalView> list(UUID requirementId) {
        return jdbc.query(SELECT + " WHERE p.requirement_id = ? AND p.state <> 'SUPERSEDED' "
            + "ORDER BY (p.state = 'PENDING') DESC, p.received_at DESC LIMIT 20", VIEW, requirementId);
    }

    @Transactional
    public PlannerProposalView accept(UUID requirementId, UUID proposalId, UUID actor) {
        PlannerProposalView p = lockPending(requirementId, proposalId);
        Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        if (r.getStatus() == RequirementStatus.APPROVED) {
            throw new IllegalStateException(r.getKey() + " is approved, so it changes only through a change request. Raise one for this"
                + " change, or reject the proposal; it stays waiting until you do.");
        }
        Requirement updated = requirementService.update(requirementId, r.getRevision(), p.title(), p.statement(), r.getType(),
            p.priority(), r.getCapabilityId(), actor);
        Set<String> have = new HashSet<>();
        for (AcceptanceCriterion c : criteria.list(requirementId)) have.add(c.getText().strip());
        int added = 0;
        for (String text : p.criteria()) {
            if (have.add(text.strip())) {
                criteria.add(requirementId, text);
                added++;
            }
        }
        jdbc.update("UPDATE planner_change_proposal SET state = 'ACCEPTED', decided_by = ?, decided_at = now(), applied_revision = ? WHERE id = ?",
            actor, updated.getRevision(), proposalId);
        audit.record(actor, "planner.change_accepted", "REQUIREMENT", requirementId, null,
            Map.of("proposalId", proposalId.toString(), "revision", updated.getRevision(), "criteriaAdded", added));
        return get(proposalId);
    }

    @Transactional
    public PlannerProposalView reject(UUID requirementId, UUID proposalId, String note, UUID actor) {
        lockPending(requirementId, proposalId);
        String clean = note == null || note.isBlank() ? null : note.strip();
        if (clean != null && clean.length() > MAX_NOTE) {
            throw new IllegalArgumentException("The note is longer than " + MAX_NOTE + " characters");
        }
        jdbc.update("UPDATE planner_change_proposal SET state = 'REJECTED', decided_by = ?, decided_at = now(), decision_note = ? WHERE id = ?",
            actor, clean, proposalId);
        audit.record(actor, "planner.change_rejected", "REQUIREMENT", requirementId, null, Map.of("proposalId", proposalId.toString()));
        return get(proposalId);
    }

    /** The proposal, locked, if it is this requirement's and still waiting; one already decided or replaced is a 409 saying so. */
    private PlannerProposalView lockPending(UUID requirementId, UUID proposalId) {
        List<String> states = jdbc.queryForList(
            "SELECT state FROM planner_change_proposal WHERE id = ? AND requirement_id = ? FOR UPDATE", String.class, proposalId, requirementId);
        if (states.isEmpty()) throw new NoSuchElementException("No such proposal on this requirement");
        String state = states.get(0);
        if (!"PENDING".equals(state)) {
            throw new IllegalStateException(switch (state) {
                case "SUPERSEDED" -> "The Planner has sent a newer version since; this one is no longer waiting";
                default -> "This proposal was already " + state.toLowerCase(java.util.Locale.ROOT);
            });
        }
        return get(proposalId);
    }

    private PlannerProposalView get(UUID proposalId) {
        return jdbc.queryForObject(SELECT + " WHERE p.id = ?", VIEW, proposalId);
    }
}
