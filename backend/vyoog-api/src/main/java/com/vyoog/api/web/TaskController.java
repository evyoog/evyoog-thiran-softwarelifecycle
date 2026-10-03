package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantService;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.tasks.Task;
import com.vyoog.tasks.TaskKind;
import com.vyoog.tasks.TaskService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0370–0374: My Work's backing data — every task, derived, never stored. */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService tasks;
    private final UserProvisioningService provisioning;
    private final GrantService grants;
    private final AuditService audit;

    public TaskController(TaskService tasks, UserProvisioningService provisioning, GrantService grants,
                           AuditService audit) {
        this.tasks = tasks;
        this.provisioning = provisioning;
        this.grants = grants;
        this.audit = audit;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record TaskView(
        String kind, String objectId, String objectLabel, String reason, String since,
        boolean blocked, String blockedByQuestion, String blockedOwedBy,
        // D10: the date Planning resolved for this requirement, and where it came from —
        // what "overdue" is measured against instead of a number chosen in the frontend.
        String dueOn, String dueSource) {}

    private static TaskView toView(Task t) {
        return new TaskView(t.kind().name(), t.objectId().toString(), t.objectLabel(), t.reason(),
            t.since() == null ? null : t.since().toString(), t.isBlocked(), t.blockedByQuestion(),
            t.blockedOwedBy() == null ? null : t.blockedOwedBy().toString(),
            t.dueOn() == null ? null : t.dueOn().toString(), t.dueSource());
    }

    @GetMapping("/mine")
    public List<TaskView> mine(@AuthenticationPrincipal Jwt jwt) {
        return tasks.tasksFor(currentUserId(jwt)).stream().map(TaskController::toView).toList();
    }

    /**
     * VYB-0373: viewing someone else's derived work. There's no dedicated "view
     * others' work" grant type in {@code access_grant}'s role list — this reuses a
     * platform-wide ADMINISTRATOR grant as the closest existing role rather than
     * inventing a tenth one the spec never named; a narrower purpose-built grant
     * would be more honest but wasn't in scope for this session.
     */
    @GetMapping("/{userId}")
    public List<TaskView> forUser(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UUID caller = currentUserId(jwt);
        if (!caller.equals(userId) && !grants.holdsPlatform(caller, AccessRole.ADMINISTRATOR)) {
            throw new AccessDeniedException("Viewing another person's work needs the ADMINISTRATOR grant");
        }
        return tasks.tasksFor(userId).stream().map(TaskController::toView).toList();
    }

    public record StalledView(String id, String key, String status, long daysInStage, int thresholdDays) {}

    @GetMapping("/stalled")
    public List<StalledView> stalled() {
        return tasks.stalled().stream()
            .map(s -> new StalledView(s.id().toString(), s.key(), s.status(), s.daysInStage(), s.thresholdDays()))
            .toList();
    }

    // ── VYB-0838 (D19): a real completion action on a derived task ───────────────────

    public record CompleteTaskRequest(@NotBlank String kind, @NotBlank String objectId) {}

    /**
     * Records that the caller dismissed this task, at its current revision — a
     * completion table, not a task table: an unrelated task that later derives from
     * the same underlying condition still shows up, and this same task reopens on its
     * own if the object's revision moves past what was recorded here.
     */
    // VYB-0906: the caller's own derived task.
    @RequiresAccess(value = AccessRule.PERSON)
    @PostMapping("/complete")
    public void complete(@RequestBody CompleteTaskRequest body, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = currentUserId(jwt);
        TaskKind kind = TaskKind.valueOf(body.kind());
        UUID objectId = UUID.fromString(body.objectId());
        tasks.complete(kind, objectId, actor);
        audit.record(actor, "task.completed", "TASK", objectId, null, Map.of("kind", kind.name()));
    }

    // VYB-0906: the caller's own derived task.
    @RequiresAccess(value = AccessRule.PERSON)
    @PostMapping("/reopen")
    public void reopen(@RequestBody CompleteTaskRequest body, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = currentUserId(jwt);
        TaskKind kind = TaskKind.valueOf(body.kind());
        UUID objectId = UUID.fromString(body.objectId());
        tasks.reopen(kind, objectId, actor);
        audit.record(actor, "task.reopened", "TASK", objectId, null, Map.of("kind", kind.name()));
    }

    public record CompletedTaskView(String kind, String objectId, String objectLabel, String completedAt) {}

    @GetMapping("/completed-today")
    public List<CompletedTaskView> completedToday(@AuthenticationPrincipal Jwt jwt) {
        return tasks.completedToday(currentUserId(jwt)).stream()
            .map(c -> new CompletedTaskView(
                c.kind().name(), c.objectId().toString(), c.objectLabel(), c.completedAt().toString()))
            .toList();
    }
}
