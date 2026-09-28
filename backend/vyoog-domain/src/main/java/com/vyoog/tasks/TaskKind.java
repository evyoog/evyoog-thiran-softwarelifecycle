package com.vyoog.tasks;

/**
 * The seven kinds VYB-0341–0347 name. There is deliberately no eighth "custom" kind
 * and no way to construct a {@link Task} outside {@link TaskService} — VYB-0340: no
 * task is ever authored, only derived from one of exactly these conditions.
 */
public enum TaskKind {
    AUTHOR_WORDING, REVIEWER_PENDING, APPROVER_AWAITING,
    DEVELOPER_IMPLEMENT, DEVELOPER_REIMPLEMENT, TESTER_VERIFY, TESTER_REVERIFY
}
