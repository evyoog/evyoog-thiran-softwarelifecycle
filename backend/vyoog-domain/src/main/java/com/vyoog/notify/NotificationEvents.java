package com.vyoog.notify;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.ScopeRef;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0955 (D47): the events people are told about, in one place: who each is for, and what it says. Each is called from inside the
 * transaction of the change that caused it, so a change that rolls back tells nobody, and nothing here sends anything itself: the
 * inbox row and the email and chat queue rows are written, and the scheduled sender does the rest.
 *
 * <p>Who is told, as chosen with the product owner: a submission tells the Reviewers where the requirement sits; a reviewed requirement
 * tells the Approvers; approval, rejection and sending back tell the requirement's author (its owner and its creator); a release
 * changing state tells the Approvers whose scope holds a requirement committed to it; a failed test tells the author; a mention tells
 * the person mentioned. The person who made the change is never told about their own action, and the author of a requirement is never
 * asked to decide it (they may not).
 *
 * <p>Chat gets one post per event (not one per person) when the administrator has switched that kind on, and carries only the title
 * and the requirement's own title and a link, never a reason or comment text.
 */
@Service
public class NotificationEvents {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final NotificationRecipients recipients;
    private final NotificationDeliveryQueue queue;
    private final NotificationSettingsService settings;
    private final NotifyProperties properties;

    public NotificationEvents(JdbcTemplate jdbc, NotificationService notifications, NotificationRecipients recipients,
                              NotificationDeliveryQueue queue, NotificationSettingsService settings, NotifyProperties properties) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.recipients = recipients;
        this.queue = queue;
        this.settings = settings;
        this.properties = properties;
    }

    private record Req(UUID id, String key, String title, UUID ownerId, UUID createdBy, ScopeRef place) {
        Set<UUID> authors() {
            Set<UUID> a = new LinkedHashSet<>();
            if (ownerId != null) a.add(ownerId);
            if (createdBy != null) a.add(createdBy);
            return a;
        }
    }

    private Req requirement(UUID id) {
        List<Req> rows = jdbc.query("""
            SELECT id, key, title, owner_id, created_by, capability_id, application_id, product_id FROM requirement WHERE id = ?
            """, (rs, n) -> new Req(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("title"),
                rs.getObject("owner_id", UUID.class), rs.getObject("created_by", UUID.class),
                NotificationRecipients.placementOf(rs.getObject("capability_id", UUID.class),
                    rs.getObject("application_id", UUID.class), rs.getObject("product_id", UUID.class))), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * A requirement moved from one status to another. {@code batchKey} (a bulk edit's batch id) makes the chat post one for the whole
     * batch rather than one per requirement; pass null for a single move.
     */
    @Transactional
    public void requirementMoved(UUID requirementId, String from, String to, String reason, UUID actor, String batchKey) {
        Req r = requirement(requirementId);
        if (r == null || to == null || to.equals(from)) return;
        String link = "/requirements/" + r.id();
        String why = reason == null || reason.isBlank() ? r.title() : "Reason: " + reason.strip();
        switch (to) {
            case "IN_REVIEW" -> announce(NotificationKind.REVIEW_REQUESTED,
                minus(recipients.holders(AccessRole.REVIEWER, r.place()), r.authors(), actor),
                "Review requested: " + r.key(), r.title(), r.title(), link, chatKey("review-requested", r.id(), batchKey));
            case "REVIEWED" -> announce(NotificationKind.APPROVAL_REQUESTED,
                minus(recipients.holders(AccessRole.APPROVER, r.place()), r.authors(), actor),
                "Decision requested: " + r.key(), r.title(), r.title(), link, chatKey("approval-requested", r.id(), batchKey));
            case "APPROVED" -> announce(NotificationKind.REQUIREMENT_APPROVED,
                minus(recipients.active(r.authors()), Set.of(), actor),
                r.key() + " was approved", r.title(), r.title(), link, chatKey("requirement-approved", r.id(), batchKey));
            case "REJECTED" -> announce(NotificationKind.REQUIREMENT_REJECTED,
                minus(recipients.active(r.authors()), Set.of(), actor),
                r.key() + " was rejected", why, r.title(), link, chatKey("requirement-rejected", r.id(), batchKey));
            case "NEEDS_REVISION" -> announce(NotificationKind.REQUIREMENT_SENT_BACK,
                minus(recipients.active(r.authors()), Set.of(), actor),
                r.key() + " needs revision", why, r.title(), link, chatKey("requirement-sent-back", r.id(), batchKey));
            default -> { /* a withdrawal back to draft tells nobody */ }
        }
    }

    /** A release changed state. */
    @Transactional
    public void releaseMoved(UUID releaseId, String from, String to, String reason, boolean overridden, UUID actor) {
        List<String> names = jdbc.queryForList("SELECT name FROM release WHERE id = ?", String.class, releaseId);
        if (names.isEmpty()) return;
        String name = names.get(0);
        Set<ScopeRef> places = new LinkedHashSet<>();
        jdbc.query("""
            SELECT DISTINCT r.capability_id, r.application_id, r.product_id
              FROM release_scope_item s JOIN requirement r ON r.id = s.requirement_id
             WHERE s.release_id = ? AND r.deleted_at IS NULL
            """, rs -> { places.add(NotificationRecipients.placementOf(rs.getObject("capability_id", UUID.class),
                rs.getObject("application_id", UUID.class), rs.getObject("product_id", UUID.class))); }, releaseId);
        Set<UUID> who = places.isEmpty() ? new TreeSet<>() : minus(recipients.holders(AccessRole.APPROVER, places), Set.of(), actor);
        String note = reason == null || reason.isBlank() ? null : reason.strip();
        String subtitle = (overridden ? "Moved past failing readiness gates" : null);
        if (note != null) subtitle = (subtitle == null ? "" : subtitle + ": ") + note;
        announce(NotificationKind.RELEASE_STATE_CHANGED, who, "Release " + name + ": " + from + " to " + to, subtitle,
            overridden ? "Moved past failing readiness gates" : null, "/releases", null);
    }

    /** A test recorded a failure against a requirement. {@code runId} makes the chat post one for the whole run. */
    @Transactional
    public void testFailed(UUID requirementId, String testName, String runLabel, UUID runId, UUID actor) {
        Req r = requirement(requirementId);
        if (r == null) return;
        String where = runLabel == null || runLabel.isBlank() ? "a test run" : runLabel;
        announce(NotificationKind.TEST_FAILED, minus(recipients.active(r.authors()), Set.of(), actor),
            "Test failed against " + r.key(), testName + " in " + where, "Tests failed in " + where + " (first seen against " + r.key() + ")",
            "/requirements/" + r.id(), runId == null ? null : "test-failed:" + runId);
    }

    /**
     * VYB-0919: the Agile Planner changed a Function whose requirement already exists here, and the change waits as a proposal. Told: the
     * people who may edit the requirement where it sits (and so may accept the change), any administrator, and its owner. Nobody is the
     * actor: the Planner sent it.
     */
    @Transactional
    public void plannerChangeProposed(UUID requirementId) {
        Req r = requirement(requirementId);
        if (r == null) return;
        Set<UUID> who = new TreeSet<>(recipients.active(r.authors()));
        for (AccessRole role : AccessRule.CREATE_EDIT_REQ.roles()) who.addAll(recipients.holders(role, r.place()));
        who.addAll(recipients.holders(AccessRole.ADMINISTRATOR, r.place()));
        announce(NotificationKind.PLANNER_CHANGE_PROPOSED, who, "Planner change proposed: " + r.key(), r.title(), r.title(),
            "/requirements/" + r.id(), null);
    }

    /** People were mentioned in a comment on a requirement. */
    @Transactional
    public void mentioned(UUID requirementId, UUID authorId, String body, Collection<UUID> mentioned) {
        Req r = requirement(requirementId);
        if (r == null || mentioned == null || mentioned.isEmpty()) return;
        List<String> names = jdbc.queryForList("SELECT display_name FROM app_user WHERE id = ?", String.class, authorId);
        String who = names.isEmpty() ? "Someone" : names.get(0);
        String excerpt = body == null ? null : (body.length() > 200 ? body.substring(0, 200) + "..." : body);
        announce(NotificationKind.MENTIONED, minus(recipients.active(new LinkedHashSet<>(mentioned)), Set.of(), authorId),
            who + " mentioned you on " + r.key(), excerpt, null, "/requirements/" + r.id(), null);
    }

    private static Set<UUID> minus(Set<UUID> from, Set<UUID> not, UUID actor) {
        Set<UUID> out = new TreeSet<>(from);
        out.removeAll(not);
        if (actor != null) out.remove(actor);
        return out;
    }

    /** A single move is its own post (no key); every move in one bulk edit shares the batch's key, so the batch is one post. */
    private static String chatKey(String kind, UUID id, String batchKey) {
        return batchKey == null ? null : kind + ":" + batchKey;
    }

    private void announce(NotificationKind kind, Collection<UUID> who, String title, String subtitle, String chatSubtitle, String link, String chatKey) {
        for (UUID u : who) {
            notifications.notify(u, kind.tone(), kind.key(), title, subtitle, link);
        }
        if (kind.chatEligible() && properties.chatEnabled() && settings.chatKindEnabled(kind.key())) {
            queue.enqueueChat(kind.key(), title, chatSubtitle, link, chatKey);
        }
    }
}
