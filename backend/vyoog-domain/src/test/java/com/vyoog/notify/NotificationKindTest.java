package com.vyoog.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** VYB-0955 (D47): the registry of kinds. */
class NotificationKindTest {

    @Test
    void VYB0955_AC1_everyKindHasAUniqueKeyAndWordsAPersonCanReadAndTheKeysTheProducersAlreadyUseAreKept() {
        List<String> keys = Arrays.stream(NotificationKind.values()).map(NotificationKind::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
        for (NotificationKind k : NotificationKind.values()) {
            assertThat(k.label()).isNotBlank();
            assertThat(k.group()).isNotBlank();
            assertThat(k.description()).isNotBlank();
            assertThat(k.tone()).isIn("info", "warn", "crit");
        }
        // the keys written before this row existed (clarifications and defects) must still resolve, or their owners lose their choices
        assertThat(keys).contains("clarification-raised", "clarification-answered", "clarification-escalated", "defect-routed-developer",
            "defect-routed-tester", "defect-fixed", "defect-reopened");
    }

    @Test
    void VYB0955_AC1_onlyEventKindsThatAreNotPersonalCanGoToChat() {
        Set<String> chat = Arrays.stream(NotificationKind.values()).filter(NotificationKind::chatEligible).map(NotificationKind::key).collect(Collectors.toSet());
        assertThat(chat).containsExactlyInAnyOrder("review-requested", "approval-requested", "requirement-approved", "requirement-rejected",
            "requirement-sent-back", "release-state-changed", "test-failed");
        assertThat(NotificationKind.MENTIONED.chatEligible()).isFalse();
    }

    @Test
    void VYB0955_AC1_theDefaultIsInboxForEveryKindAndEmailOnlyForTheUrgentOnes() {
        for (NotificationKind k : NotificationKind.values()) {
            assertThat(NotificationKind.defaultInbox(k.key())).isTrue();
            assertThat(NotificationKind.defaultEmail(k.key())).isEqualTo(k.urgent());
        }
        assertThat(NotificationKind.defaultEmail("a-kind-from-the-future")).isFalse();
        assertThat(NotificationKind.defaultInbox("a-kind-from-the-future")).isTrue();
        assertThat(NotificationKind.find("nope")).isEmpty();
    }
}
