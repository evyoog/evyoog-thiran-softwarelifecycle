package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** VYB-0939: the prompt version is a short fingerprint of the system prompt, so a changed prompt is a new version in the usage report. */
class ChatRequestPromptVersionTest {

    @Test
    void VYB0939_AC12_theSameSystemPromptAlwaysGivesTheSameEightCharacterVersion() {
        String a = ChatRequest.interactive("p", "You rewrite requirements.", "one", 10, 0).promptVersion();
        String b = ChatRequest.batch("q", "You rewrite requirements.", "two", 99, 0.5, Duration.ofSeconds(5)).promptVersion();

        assertThat(a).isEqualTo(b).matches("[0-9a-f]{8}");
    }

    @Test
    void VYB0939_AC12_aChangedSystemPromptIsANewVersion() {
        assertThat(ChatRequest.promptVersionOf("You rewrite requirements."))
            .isNotEqualTo(ChatRequest.promptVersionOf("You rewrite requirements!"));
    }

    @Test
    void VYB0939_AC12_theUserTextDoesNotChangeTheVersionBecauseItIsNotThePrompt() {
        ChatRequest r = ChatRequest.interactive("p", "System.", "first", 10, 0);
        assertThat(r.withUser("second").promptVersion()).isEqualTo(r.promptVersion());
    }

    @Test
    void VYB0939_AC12_theKnownVersionOfAKnownPromptIsPinnedSoAnAccidentalChangeToTheHashingIsCaught() {
        // sha256("abc") = ba7816bf8f01cfea…
        assertThat(ChatRequest.promptVersionOf("abc")).isEqualTo("ba7816bf");
    }
}
