package com.vyoog.integration.planner;

import com.vyoog.platform.IdempotencyService;
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
 *   <li>The same {@code Idempotency-Key} answers with the requirement it first made. The same Function sent again under another key with
 *       the same content answers with the requirement it already has. The same Function with changed content is refused (409) until
 *       change proposals exist: nothing is applied behind a person's back, and nothing is lost, because the Planner still holds it.</li>
 *   <li>Two sends of one Function at the same moment create one requirement (a unique index decides; the loser answers as a repeat).</li>
 *   <li>The creation is audited as done by a service account.</li>
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

    /** What happened: the requirement, and whether this call made it. */
    public record Outcome(UUID requirementId, String key, String status, Instant createdAt, boolean created) {}

    /** The Function was received before with different content; applying it is a later row (a proposal for a person to decide). */
    public static class FunctionChangedException extends IllegalStateException {
        public FunctionChangedException(String key, String functionId) {
            super("Function " + functionId + " already created " + key + " here and has changed since. A change to a Function the Planner "
                + "has sent is not applied automatically; it will arrive as a proposal for a person to accept once that exists. "
                + "Nothing was changed.");
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IdempotencyService idempotency;
    private final RequirementService requirementService;
    private final AcceptanceCriterionService criteria;
    private final RequirementRepository requirements;

    public PlannerRequirementIntake(JdbcTemplate jdbc, PlatformTransactionManager transactions, IdempotencyService idempotency,
                                    RequirementService requirementService, AcceptanceCriterionService criteria,
                                    RequirementRepository requirements) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.idempotency = idempotency;
        this.requirementService = requirementService;
        this.criteria = criteria;
        this.requirements = requirements;
    }

    /** Not transactional itself: each attempt is its own transaction, so a lost race rolls back cleanly and is retried as a repeat. */
    public Outcome receive(String idempotencyKey, Push push) {
        validate(idempotencyKey, push);
        String hash = contentHash(push);
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            var replay = idempotency.find(idempotencyKey, IDEMPOTENCY_ENDPOINT);
            if (replay.isPresent()) {
                return outcomeOf(replay.get(), false);
            }
            Map<String, Object> link = findLink(push.sourceFunctionId());
            if (link != null) {
                UUID requirementId = (UUID) link.get("requirement_id");
                if (!hash.equals(link.get("content_hash"))) {
                    throw new FunctionChangedException(requirements.findById(requirementId).orElseThrow().getKey(), push.sourceFunctionId());
                }
                idempotency.record(idempotencyKey, IDEMPOTENCY_ENDPOINT, requirementId); // best effort: a later repeat is then quick
                return outcomeOf(requirementId, false);
            }
            try {
                UUID id = tx.execute(status -> createAll(idempotencyKey, push, hash));
                return outcomeOf(id, true);
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
            "SELECT requirement_id, content_hash FROM planner_function_link WHERE source_system = ? AND source_function_id = ?",
            SOURCE_SYSTEM, functionId.strip());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Outcome outcomeOf(UUID requirementId, boolean created) {
        Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        return new Outcome(r.getId(), r.getKey(), r.getStatus().name(), r.getCreatedAt(), created);
    }

    // ------------------------------------------------------------------ rules (static so they can be tested without a database)

    static void validate(String idempotencyKey, Push p) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("The Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_ID) {
            throw new IllegalArgumentException("The Idempotency-Key is longer than " + MAX_ID + " characters");
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
