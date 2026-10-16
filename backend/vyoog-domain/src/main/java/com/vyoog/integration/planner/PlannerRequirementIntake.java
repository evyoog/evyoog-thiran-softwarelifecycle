package com.vyoog.integration.planner;

import com.vyoog.notify.NotificationEvents;
import com.vyoog.platform.IdempotencyService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.Placement;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0915 (D24, D50): receives a Function from the Agile Planner and creates the draft requirement it seeds.
 *
 * <p>Rules (see {@code docs/09-integrations/agile-planner-requirements-intake.md}):
 * <ul>
 *   <li>One Function is one requirement, linked by {@code source_function_id}. The requirement is a draft, of type FUNCTIONAL, placed
 *       nowhere (the Planner's capability id belongs to a system this one cannot map yet), with one acceptance criterion per string sent.
 *       From then on it is ours: nothing the Planner sends again overwrites it.</li>
 *   <li>The same {@code Idempotency-Key} answers with what it first did. The same Function sent again under another key with the same
 *       content answers with the requirement it already has. The same Function with changed content becomes a <b>proposal</b> on its
 *       requirement (VYB-0919): nothing is applied behind a person's back; a person accepts or rejects it ({@link PlannerProposalService}).
 *       A newer change supersedes an older undecided one, and the requirement's own current words sent again close it.</li>
 *   <li>Two sends of one Function at the same moment create one requirement (a unique index decides; the loser answers as a repeat),
 *       and changes to one Function are decided one at a time under a lock on its link.</li>
 *   <li>The creation and each proposal are audited as done by a service account.</li>
 * </ul>
 */
@Service
public class PlannerRequirementIntake {

    private static final Logger log = LoggerFactory.getLogger(PlannerRequirementIntake.class);

    public static final String SOURCE_SYSTEM = "agile_planner";
    static final String IDEMPOTENCY_ENDPOINT = "planner.requirements";
    static final int MAX_ID = 200;
    static final int MAX_TITLE = 300;
    static final int MAX_TEXT = 20_000;
    static final int MAX_CRITERIA = 50;
    static final int MAX_CRITERION = 2_000;
    private static final int ATTEMPTS = 3;

    /** What the Planner sent, as this application understands it. */
    public record Push(String sourceSystem, String sourceFunctionId, String sourceFeatureId, String sourceCapabilityId,
                       String sourceGoalId, String title, String description, String priority, List<String> acceptanceCriteria) {}

    /**
     * What happened: the requirement, whether this call made it, and, when the Function had changed, the proposal that holds the change
     * (with the state it is in now).
     */
    public record Outcome(UUID requirementId, String key, String status, Instant createdAt, boolean created,
                          UUID proposalId, String proposalState) {
        public boolean proposed() {
            return proposalId != null;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IdempotencyService idempotency;
    private final RequirementService requirementService;
    private final AcceptanceCriterionService criteria;
    private final RequirementRepository requirements;
    private final AuditService audit;
    private final NotificationEvents events;

    public PlannerRequirementIntake(JdbcTemplate jdbc, PlatformTransactionManager transactions, IdempotencyService idempotency,
                                    RequirementService requirementService, AcceptanceCriterionService criteria,
                                    RequirementRepository requirements, AuditService audit, NotificationEvents events) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.idempotency = idempotency;
        this.requirementService = requirementService;
        this.criteria = criteria;
        this.requirements = requirements;
        this.audit = audit;
        this.events = events;
    }

    /** Not transactional itself: each attempt is its own transaction, so a lost race rolls back cleanly and is retried as a repeat. */
    public Outcome receive(String idempotencyKey, Push push) {
        validate(idempotencyKey, push);
        String hash = contentHash(push);
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            var replay = idempotency.find(idempotencyKey, IDEMPOTENCY_ENDPOINT);
            if (replay.isPresent()) {
                return replayOutcome(replay.get(), idempotencyKey);
            }
            Map<String, Object> link = findLink(push.sourceFunctionId());
            if (link != null) {
                return tx.execute(status -> receiveKnown((UUID) link.get("id"), idempotencyKey, push, hash));
            }
            try {
                UUID id = tx.execute(status -> createAll(idempotencyKey, push, hash));
                return outcomeOf(id, true, null);
            } catch (DataIntegrityViolationException | RaceLostException lost) {
                log.debug("[planner] another send of {} won; answering as a repeat (attempt {})", push.sourceFunctionId(), attempt);
            }
        }
        throw new IllegalStateException("Could not settle two sends of the same Function; try again");
    }

    private static final class RaceLostException extends RuntimeException {
        RaceLostException() {
            super(null, null, false, false);
        }
    }

    private UUID createAll(String idempotencyKey, Push push, String hash) {
        Requirement r = requirementService.createByService(
            push.title().strip(), statementOf(push), "FUNCTIONAL", priorityOf(push.priority()), Placement.unplaced());
        for (String criterion : cleanCriteria(push.acceptanceCriteria())) {
            criteria.add(r.getId(), criterion);
        }
        jdbc.update("""
            INSERT INTO planner_function_link
              (source_system, source_function_id, source_feature_id, source_capability_id, source_goal_id, requirement_id, content_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?)""",
            SOURCE_SYSTEM, push.sourceFunctionId().strip(), blankToNull(push.sourceFeatureId()),
            blankToNull(push.sourceCapabilityId()), blankToNull(push.sourceGoalId()), r.getId(), hash);
        if (!idempotency.record(idempotencyKey, IDEMPOTENCY_ENDPOINT, r.getId())) {
            throw new RaceLostException(); // the same key is being handled by another request: undo this attempt, answer as its repeat
        }
        return r.getId();
    }

    private Map<String, Object> findLink(String functionId) {
        var rows = jdbc.queryForList(
            "SELECT id, requirement_id, content_hash FROM planner_function_link WHERE source_system = ? AND source_function_id = ?",
            SOURCE_SYSTEM, functionId.strip());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * A Function this application already has a requirement for, sent again. Runs in a transaction that holds a lock on the Function's
     * link, so two sends of one Function are decided one after the other, each against what the first left.
     */
    private Outcome receiveKnown(UUID linkId, String idempotencyKey, Push push, String hash) {
        Map<String, Object> link = jdbc.queryForMap(
            "SELECT requirement_id, content_hash, source_function_id FROM planner_function_link WHERE id = ? FOR UPDATE", linkId);
        UUID requirementId = (UUID) link.get("requirement_id");
        if (hash.equals(link.get("content_hash"))) {
            // the same words as the last send: a repeat, or the proposal that send made, still waiting
            rememberKey(idempotencyKey, requirementId);
            return pendingOutcome(linkId, requirementId);
        }
        if (hash.equals(currentContentHash(requirementId))) {
            // the Planner sent the requirement's own words again: whatever it offered before no longer differs, so nothing is left to decide
            supersedePending(linkId);
            jdbc.update("UPDATE planner_function_link SET content_hash = ? WHERE id = ?", hash, linkId);
            rememberKey(idempotencyKey, requirementId);
            return outcomeOf(requirementId, false, null);
        }
        supersedePending(linkId);
        UUID proposalId = jdbc.queryForObject("""
            INSERT INTO planner_change_proposal (link_id, requirement_id, title, statement, priority, criteria, content_hash, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
            linkId, requirementId, push.title().strip(), statementOf(push), priorityOf(push.priority()),
            cleanCriteria(push.acceptanceCriteria()).toArray(new String[0]), hash, idempotencyKey);
        jdbc.update("UPDATE planner_function_link SET content_hash = ? WHERE id = ?", hash, linkId);
        rememberKey(idempotencyKey, requirementId);
        audit.recordService("planner.change_proposed", "REQUIREMENT", requirementId,
            Map.of("proposalId", proposalId.toString(), "sourceFunctionId", String.valueOf(link.get("source_function_id"))));
        events.plannerChangeProposed(requirementId);
        return outcomeOf(requirementId, false, proposalId);
    }

    /** Inside the link's lock, so a find then a record cannot race another send of the same Function. */
    private void rememberKey(String idempotencyKey, UUID requirementId) {
        if (idempotency.find(idempotencyKey, IDEMPOTENCY_ENDPOINT).isEmpty()) {
            idempotency.record(idempotencyKey, IDEMPOTENCY_ENDPOINT, requirementId);
        }
    }

    private void supersedePending(UUID linkId) {
        jdbc.update("UPDATE planner_change_proposal SET state = 'SUPERSEDED' WHERE link_id = ? AND state = 'PENDING'", linkId);
    }

    /** The hash of what the requirement says now, in the same terms as a push, so the two can be compared. */
    private String currentContentHash(UUID requirementId) {
        Map<String, Object> r = jdbc.queryForMap("SELECT title, statement, priority FROM requirement WHERE id = ?", requirementId);
        List<String> texts = jdbc.queryForList(
            "SELECT text FROM acceptance_criterion WHERE requirement_id = ? ORDER BY ordinal", String.class, requirementId);
        return contentHash(new Push(SOURCE_SYSTEM, null, null, null, null, (String) r.get("title"), (String) r.get("statement"),
            (String) r.get("priority"), texts));
    }

    private Outcome pendingOutcome(UUID linkId, UUID requirementId) {
        var rows = jdbc.queryForList("SELECT id, state FROM planner_change_proposal WHERE link_id = ? AND state = 'PENDING'", linkId);
        return rows.isEmpty() ? outcomeOf(requirementId, false, null) : outcomeOf(requirementId, false, (UUID) rows.get(0).get("id"));
    }

    /** A key seen before: the proposal it made if it made one, else the requirement. */
    private Outcome replayOutcome(UUID requirementId, String idempotencyKey) {
        var rows = jdbc.queryForList("SELECT id FROM planner_change_proposal WHERE idempotency_key = ?", idempotencyKey);
        return outcomeOf(requirementId, false, rows.isEmpty() ? null : (UUID) rows.get(0).get("id"));
    }

    private Outcome outcomeOf(UUID requirementId, boolean created, UUID proposalId) {
        Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        String state = proposalId == null ? null
            : jdbc.queryForObject("SELECT state FROM planner_change_proposal WHERE id = ?", String.class, proposalId);
        return new Outcome(r.getId(), r.getKey(), r.getStatus().name(), r.getCreatedAt(), created, proposalId, state);
    }

    // ------------------------------------------------------------------ rules (static so they can be tested without a database)

    static void validate(String idempotencyKey, Push p) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("The Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_ID) {
            throw new IllegalArgumentException("The Idempotency-Key is longer than " + MAX_ID + " characters");
        }
        // printable ASCII only (the Planner's own key is a Function id and a timestamp): nothing that could be a control character in a log
        if (!idempotencyKey.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            throw new IllegalArgumentException("The Idempotency-Key may hold only printable ASCII characters without spaces");
        }
        if (p == null) {
            throw new IllegalArgumentException("A body is required");
        }
        if (!SOURCE_SYSTEM.equals(p.sourceSystem())) {
            throw new IllegalArgumentException("source_system must be '" + SOURCE_SYSTEM + "'");
        }
        requireText(p.sourceFunctionId(), "source_function_id", MAX_ID);
        requireText(p.title(), "title", MAX_TITLE);
        if (p.description() != null && p.description().length() > MAX_TEXT) {
            throw new IllegalArgumentException("description is longer than " + MAX_TEXT + " characters");
        }
        for (String id : new String[] {p.sourceFeatureId(), p.sourceCapabilityId(), p.sourceGoalId()}) {
            if (id != null && id.length() > MAX_ID) {
                throw new IllegalArgumentException("A source id is longer than " + MAX_ID + " characters");
            }
        }
        List<String> ac = p.acceptanceCriteria();
        if (ac != null) {
            if (ac.size() > MAX_CRITERIA) {
                throw new IllegalArgumentException("acceptance_criteria holds more than " + MAX_CRITERIA + " items");
            }
            for (String c : ac) {
                if (c != null && c.length() > MAX_CRITERION) {
                    throw new IllegalArgumentException("An acceptance criterion is longer than " + MAX_CRITERION + " characters");
                }
            }
        }
    }

    private static void requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.strip().length() > max) {
            throw new IllegalArgumentException(name + " is longer than " + max + " characters");
        }
    }

    /** The Planner's "high" is HIGH here, whatever the case; anything else it sends (or nothing) is MEDIUM. */
    static String priorityOf(String planner) {
        if (planner == null) return "MEDIUM";
        return switch (planner.strip().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "LOW" -> "LOW";
            default -> "MEDIUM";
        };
    }

    /** The statement is the Function's description; a Function with none is stated by its title. */
    static String statementOf(Push p) {
        return p.description() == null || p.description().isBlank() ? p.title().strip() : p.description().strip();
    }

    static List<String> cleanCriteria(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String c : raw) {
                if (c != null && !c.isBlank()) out.add(c.strip());
            }
        }
        return out;
    }

    /** What the Planner sent, normalised so that a repeat with only a new timestamp in its key is recognised as the same content. */
    static String contentHash(Push p) {
        StringBuilder b = new StringBuilder();
        b.append(p.title().strip()).append('\u0000')
            .append(statementOf(p)).append('\u0000')
            .append(priorityOf(p.priority())).append('\u0000');
        for (String c : cleanCriteria(p.acceptanceCriteria())) b.append(c).append('\u0001');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
