package com.vyoog.requirements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0941 (F18): one timeline for a requirement, newest first, of what was written and what was decided:
 * <ul>
 *   <li>a <b>revision</b>: the immutable snapshot {@code requirement_revision} keeps for the creation and every material
 *       edit (V001, VYB-0112), complete enough that any two can be compared;</li>
 *   <li>a <b>status change</b>: a move between the six statuses with its reason, from the {@code requirement.transitioned}
 *       audit event. A status change does not make a revision (the words did not change), so it is only on the audit log.</li>
 * </ul>
 * Nothing here is written. The audit log is partitioned and old partitions are archived (VYB-0723), so status changes older
 * than the retention window may be missing; {@link History#statusHistoryMayBeIncomplete()} says when the requirement's own
 * creation is no longer in the live log. Revisions are never archived.
 */
@Service
public class RequirementHistoryService {

    public static final String REVISION = "REVISION";
    public static final String STATUS_CHANGE = "STATUS_CHANGE";

    /**
     * One row of the timeline. {@code kind} says which fields are set: a REVISION has the snapshot fields (and
     * {@code statusAtRevision}), a STATUS_CHANGE has {@code fromStatus} and {@code toStatus}. {@code reason} is the change
     * reason of a revision, or the reason given for a status change. A person who is no longer known has a null name.
     */
    public record Entry(String kind, Instant at, UUID actorId, String actorName, String reason,
                        Integer revision, String title, String statement, String type, String priority,
                        UUID capabilityId, String capabilityName, String statusAtRevision,
                        String fromStatus, String toStatus) {}

    public record History(UUID requirementId, int currentRevision, String currentStatus,
                          boolean statusHistoryMayBeIncomplete, List<Entry> entries) {}

    private final JdbcTemplate jdbc;

    public RequirementHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws NoSuchElementException no such requirement */
    public History historyOf(UUID requirementId) {
        var head = jdbc.query("SELECT revision, status FROM requirement WHERE id = ?",
            rs -> rs.next() ? new Object[] {rs.getInt(1), rs.getString(2)} : null, requirementId);
        if (head == null) throw new NoSuchElementException("No such requirement");

        List<Entry> entries = new ArrayList<>(jdbc.query("""
            SELECT rv.revision, rv.title, rv.statement, rv.type, rv.priority, rv.status, rv.capability_id, c.name AS capability_name,
                   rv.changed_by, u.display_name, rv.changed_at, rv.change_reason
            FROM requirement_revision rv
            LEFT JOIN app_user u ON u.id = rv.changed_by
            LEFT JOIN capability c ON c.id = rv.capability_id
            WHERE rv.requirement_id = ?
            """, (rs, n) -> new Entry(REVISION, rs.getTimestamp("changed_at").toInstant(), uuid(rs.getString("changed_by")),
                rs.getString("display_name"), rs.getString("change_reason"), rs.getInt("revision"), rs.getString("title"),
                rs.getString("statement"), rs.getString("type"), rs.getString("priority"), uuid(rs.getString("capability_id")),
                rs.getString("capability_name"), rs.getString("status"), null, null), requirementId));

        entries.addAll(jdbc.query("""
            SELECT a.occurred_at, a.actor_id, u.display_name, a.after->>'previousStatus' AS from_status,
                   a.after->>'status' AS to_status, a.after->>'reason' AS reason
            FROM audit_event a
            LEFT JOIN app_user u ON u.id = a.actor_id
            WHERE a.object_type = 'REQUIREMENT' AND a.object_id = ? AND a.action = 'requirement.transitioned'
            """, (rs, n) -> new Entry(STATUS_CHANGE, rs.getTimestamp("occurred_at").toInstant(), uuid(rs.getString("actor_id")),
                rs.getString("display_name"), blankToNull(rs.getString("reason")), null, null, null, null, null, null, null, null,
                rs.getString("from_status"), rs.getString("to_status")), requirementId));

        // Newest first; at the same instant the status change is shown above the revision it followed.
        entries.sort(Comparator.comparing(Entry::at).reversed()
            .thenComparing((Entry e) -> e.kind().equals(STATUS_CHANGE) ? 0 : 1)
            .thenComparing(e -> e.revision() == null ? 0 : -e.revision()));

        Boolean createdOnLog = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM audit_event WHERE object_type = 'REQUIREMENT' AND object_id = ? AND action = 'requirement.created')
            """, Boolean.class, requirementId);
        return new History(requirementId, (Integer) head[0], (String) head[1], !Boolean.TRUE.equals(createdOnLog), List.copyOf(entries));
    }

    private static UUID uuid(String text) {
        return text == null ? null : UUID.fromString(text);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
