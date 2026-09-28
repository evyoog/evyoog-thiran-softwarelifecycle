package com.vyoog.tasks;

import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0348: {@code reason} always names the condition that derived this, e.g.
 * "Approved 6 days ago with no code linked" — never a bare label. VYB-0374: {@code
 * blockedByQuestion}/{@code blockedOwedBy} are set only for a task whose one
 * requirement currently has an open blocking clarification; reviewer/approver tasks
 * span a whole round rather than one requirement, so blocking isn't tracked on them.
 *
 * @param onBehalfOfUserId VYB-0706 AC2: null for a person's own task; set to the
 *     original owner's id when this task is appearing for their nominated delegate
 *     instead — accountability keeps naming who the task is actually about even
 *     though the delegate is who now sees it.
 */
public record Task(
    TaskKind kind, UUID objectId, String objectLabel, String reason, Instant since,
    String blockedByQuestion, UUID blockedOwedBy, UUID onBehalfOfUserId,
    /**
     * When the requirement behind this task is due, resolved by {@code PlanningService} —
     * an explicit target date, the date of the release it is committed to, or the stage
     * threshold. Null when nothing knows, which is why "overdue" has to tolerate its
     * absence rather than treating no date as due today.
     */
    java.time.LocalDate dueOn, String dueSource) {

    /** Pre-date shape — every existing construction site stays valid and simply has no date. */
    public Task(TaskKind kind, UUID objectId, String objectLabel, String reason, Instant since,
                String blockedByQuestion, UUID blockedOwedBy, UUID onBehalfOfUserId) {
        this(kind, objectId, objectLabel, reason, since, blockedByQuestion, blockedOwedBy, onBehalfOfUserId, null, null);
    }

    /** The same task with its due date attached, once Planning has resolved one. */
    public Task withDue(java.time.LocalDate dueOn, String dueSource) {
        return new Task(kind, objectId, objectLabel, reason, since, blockedByQuestion, blockedOwedBy,
            onBehalfOfUserId, dueOn, dueSource);
    }

    /** The pre-delegation shape — every existing call site stays a "my own task." */
    public Task(TaskKind kind, UUID objectId, String objectLabel, String reason, Instant since,
                String blockedByQuestion, UUID blockedOwedBy) {
        this(kind, objectId, objectLabel, reason, since, blockedByQuestion, blockedOwedBy, null);
    }

    public boolean isBlocked() { return blockedByQuestion != null; }

    /** VYB-0706 AC1: the same task, now carrying whose behalf it's on. */
    public Task onBehalfOf(UUID originalOwnerId) {
        return new Task(kind, objectId, objectLabel, reason, since, blockedByQuestion, blockedOwedBy, originalOwnerId);
    }
}
