package com.vyoog.notify;

import java.util.Arrays;
import java.util.Optional;

/**
 * VYB-0955 (D47): every kind of notification the product sends, with the words a person sees when choosing, whether it is urgent, and
 * whether it can be posted to the team chat channel. The {@code key} is what is stored on the inbox row and in the preferences; keys
 * already in use before this row (clarifications and defects) keep theirs.
 *
 * <p>Urgent means someone must act on it: its email goes straight away (the first of a kind within the digest window; later ones of the
 * same kind wait for the digest), and email is on by default for it. Everything else is inbox-only until the person turns email on, and
 * then arrives batched. Chat is for events about the work as a whole, so only the event kinds that are not personal are chat-eligible.
 */
public enum NotificationKind {

    REVIEW_REQUESTED("review-requested", "Review requested", "Reviews and decisions",
        "A requirement was submitted and you hold the Reviewer role where it sits.", "warn", true, true),
    APPROVAL_REQUESTED("approval-requested", "Decision requested", "Reviews and decisions",
        "A reviewed requirement is waiting for an approval decision and you hold the Approver role where it sits.", "warn", true, true),
    REQUIREMENT_APPROVED("requirement-approved", "Requirement approved", "Reviews and decisions",
        "A requirement you own or created was approved.", "info", false, true),
    REQUIREMENT_REJECTED("requirement-rejected", "Requirement rejected", "Reviews and decisions",
        "A requirement you own or created was rejected.", "warn", true, true),
    REQUIREMENT_SENT_BACK("requirement-sent-back", "Requirement sent back for revision", "Reviews and decisions",
        "A requirement you own or created needs changes before it can go on.", "warn", true, true),
    RELEASE_STATE_CHANGED("release-state-changed", "Release moved", "Releases",
        "A release that holds requirements in your scope changed state (opened, frozen, released).", "info", false, true),
    TEST_FAILED("test-failed", "Test failed", "Quality",
        "A test recorded a failure against a requirement you own or created.", "warn", false, true),
    PLANNER_CHANGE_PROPOSED("planner-change-proposed", "Planner change proposed", "Reviews and decisions",
        "The Agile Planner changed a Function whose requirement you can edit; a person decides whether to accept the change.", "warn", true, false),
    MENTIONED("mentioned", "You were mentioned", "Conversations",
        "Someone mentioned you in a comment.", "info", true, false),
    CLARIFICATION_RAISED("clarification-raised", "Clarification asked of you", "Conversations",
        "A clarification was raised and assigned to you.", "warn", true, false),
    CLARIFICATION_ANSWERED("clarification-answered", "Your clarification was answered", "Conversations",
        "Someone answered a clarification you raised.", "info", false, false),
    CLARIFICATION_ESCALATED("clarification-escalated", "Clarification escalated", "Conversations",
        "A clarification went unanswered and was escalated to you.", "crit", true, false),
    DEFECT_ROUTED_DEVELOPER("defect-routed-developer", "Defect routed to you", "Defects",
        "A defect was routed to you to fix.", "warn", true, false),
    DEFECT_ROUTED_TESTER("defect-routed-tester", "Defect to verify", "Defects",
        "A defect on a requirement you verified, or one for you to verify, was raised.", "warn", true, false),
    DEFECT_FIXED("defect-fixed", "Defect marked fixed", "Defects",
        "A defect you are to verify was marked fixed.", "info", false, false),
    DEFECT_REOPENED("defect-reopened", "Defect reopened", "Defects",
        "A defect you fixed was reopened.", "warn", true, false);

    private final String key;
    private final String label;
    private final String group;
    private final String description;
    private final String tone;
    private final boolean urgent;
    private final boolean chatEligible;

    NotificationKind(String key, String label, String group, String description, String tone, boolean urgent, boolean chatEligible) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.description = description;
        this.tone = tone;
        this.urgent = urgent;
        this.chatEligible = chatEligible;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String group() { return group; }
    public String description() { return description; }
    public String tone() { return tone; }
    public boolean urgent() { return urgent; }
    public boolean chatEligible() { return chatEligible; }

    /** A kind written by something that predates the registry (or a test) is unknown: it keeps the inbox on and never sends email. */
    public static Optional<NotificationKind> find(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    public static boolean defaultInbox(String key) { return true; }

    public static boolean defaultEmail(String key) { return find(key).map(NotificationKind::urgent).orElse(false); }
}
