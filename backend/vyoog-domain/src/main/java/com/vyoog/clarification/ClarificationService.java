package com.vyoog.clarification;

import com.vyoog.notify.NotificationService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0330–0334. Like {@link com.vyoog.detection.DetectionSweepService}, the
 * escalation sweep lives here rather than in vyoog-worker — that module has no Spring
 * Boot application of its own yet.
 */
@Service
public class ClarificationService {

    private static final Logger log = LoggerFactory.getLogger(ClarificationService.class);

    private final ClarificationRepository clarifications;
    private final RequirementRepository requirements;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public ClarificationService(ClarificationRepository clarifications, RequirementRepository requirements,
                                 AppUserRepository users, NotificationService notifications,
                                 AuditService audit, JdbcTemplate jdbc) {
        this.clarifications = clarifications;
        this.requirements = requirements;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** VYB-0330 AC1/AC2: always names a requirement; blocking defaults to true. */
    @Transactional
    public Clarification raise(UUID requirementId, String question, UUID assignedTo, boolean blocksTask, UUID actor) {
        Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        Clarification c = clarifications.save(new Clarification(requirementId, question, blocksTask, actor, assignedTo));
        audit.record(actor, "clarification.raised", "CLARIFICATION", c.getId(), null,
            Map.of("requirementKey", r.getKey(), "blocksTask", blocksTask));
        if (assignedTo != null) {
            notifications.notify(assignedTo, "warn", "clarification-raised", "Clarification raised on " + r.getKey(), question,
                "/requirements/" + r.getId());
        }
        return c;
    }

    /** VYB-0332/0381: only the assignee or their delegate may answer. */
    @Transactional
    public Clarification answer(UUID id, String answerText, UUID actor) {
        Clarification c = clarifications.findById(id).orElseThrow(NoSuchElementException::new);
        if (!canAnswer(c, actor)) {
            throw new IllegalStateException("Only the assignee or their delegate may answer this");
        }
        c.answer(answerText, actor);
        clarifications.save(c);
        audit.record(actor, "clarification.answered", "CLARIFICATION", c.getId(), null, Map.of());
        // VYB-0332 AC2: the raiser is told, not left to notice on their own.
        notifications.notify(c.getRaisedBy(), "info", "clarification-answered", "Your clarification was answered", answerText,
            "/requirements/" + c.getRequirementId());
        return c;
    }

    private boolean canAnswer(Clarification c, UUID actor) {
        if (c.getAssignedTo() == null) return true; // unassigned — anyone with access may pick it up
        if (c.getAssignedTo().equals(actor)) return true;
        AppUser assignee = users.findById(c.getAssignedTo()).orElse(null);
        return assignee != null && actor.equals(assignee.getDelegateId());
    }

    /** VYB-0333: offered, not forced — the caller (frontend) decides whether to take this path. */
    @Transactional
    public void linkToChangeRequest(UUID clarificationId, UUID changeRequestId) {
        Clarification c = clarifications.findById(clarificationId).orElseThrow(NoSuchElementException::new);
        c.linkChangeRequest(changeRequestId);
        clarifications.save(c);
    }

    public List<Clarification> forRequirement(UUID requirementId) {
        return clarifications.findAllByRequirementIdOrderByRaisedAtAsc(requirementId);
    }

    public List<Clarification> openBlocking() {
        return clarifications.findAllByStateAndBlocksTaskTrue(ClarificationState.OPEN);
    }

    /** VYB-0334: escalates once per ageing clarification, never repeatedly. */
    @Scheduled(cron = "0 30 2 * * *")
    @Transactional
    public void escalateAgeing() {
        Integer thresholdDays = jdbc.queryForObject(
            "SELECT clarification_escalation_days FROM app_config WHERE id = 1", Integer.class);
        Instant cutoff = Instant.now().minus(Duration.ofDays(thresholdDays == null ? 3 : thresholdDays));
        List<Clarification> ageing = clarifications.findAllByStateAndEscalatedAtIsNullAndRaisedAtBefore(
            ClarificationState.OPEN, cutoff);
        for (Clarification c : ageing) {
            try {
                escalateOne(c);
            } catch (Exception e) {
                log.warn("[clarification] escalation failed for {}: {}", c.getId(), e.getMessage());
            }
        }
    }

    /**
     * VYB-0334/0792 AC1: "notifies the assignee's manager or the capability owner" —
     * app_user gained a real manager_id (V013), so this now follows that literal
     * wording: the assignee's manager first (if there's an assignee and they have
     * one set), then the capability owner, then whoever raised it if neither exists.
     * The fallback chain from before this session is unchanged, not replaced.
     */
    private void escalateOne(Clarification c) {
        UUID escalateTo = null;
        if (c.getAssignedTo() != null) {
            escalateTo = users.findById(c.getAssignedTo()).map(AppUser::getManagerId).orElse(null);
        }
        if (escalateTo == null) {
            Requirement r = requirements.findById(c.getRequirementId()).orElse(null);
            if (r != null && r.getCapabilityId() != null) {
                escalateTo = jdbc.query("SELECT owner_id FROM capability WHERE id = ?",
                    (rs, n) -> rs.getString("owner_id") == null ? null : UUID.fromString(rs.getString("owner_id")),
                    r.getCapabilityId()).stream().findFirst().orElse(null);
            }
        }
        if (escalateTo == null) escalateTo = c.getRaisedBy();

        c.markEscalated(escalateTo);
        clarifications.save(c);
        long days = ChronoUnit.DAYS.between(c.getRaisedAt(), Instant.now());
        notifications.notify(escalateTo, "crit", "clarification-escalated",
            "Clarification open %d days with no answer".formatted(days),
            c.getQuestion(), "/requirements/" + c.getRequirementId());
        audit.recordSystem("clarification.escalated", "CLARIFICATION", c.getId(),
            Map.of("escalatedTo", escalateTo.toString(), "daysOpen", days));
    }
}
