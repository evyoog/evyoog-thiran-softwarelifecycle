package com.vyoog.tasks;

import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0838 (D19): a person dismissed one derived task once, at {@code objectRevision}
 * (null for a review-scoped kind, which has no revision). Never a task itself — see
 * {@code V034__task_completion.sql} and {@link TaskService#complete}.
 */
public record TaskCompletion(UUID id, TaskKind kind, UUID objectId, Integer objectRevision,
                              UUID userId, Instant completedAt) {}
