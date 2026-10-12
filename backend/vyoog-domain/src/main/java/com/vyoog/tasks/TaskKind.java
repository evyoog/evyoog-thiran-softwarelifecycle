package com.vyoog.tasks;

/**
 * The five kinds VYB-0341 and VYB-0344–0347 name (VYB-0342/0343, the reviewer and approver round tasks, were retired with review rounds, D26). There is deliberately no "custom" kind
 * and no way to construct a {@link Task} outside {@link TaskService} — VYB-0340: no
 * task is ever authored, only derived from one of exactly these conditions.
 */
public enum TaskKind {
    AUTHOR_WORDING,
    DEVELOPER_IMPLEMENT, DEVELOPER_REIMPLEMENT, TESTER_VERIFY, TESTER_REVERIFY
}
