package com.vyoog.api;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.LlmAdjudicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VYB-0612/0655: {@link com.vyoog.ai.OpenAiLlmAdjudicator} — the bean {@code
 * ConflictingRequirementsDetector} has been waiting for since session 11 — proven
 * against a real HTTP round trip to a throwaway local server standing in for
 * OpenAI's Chat Completions endpoint. Same explicit-name-only convention as every
 * other {@code *VerificationRunner}: invisible to {@code mvn test}/{@code mvn
 * verify}.
 *
 * <pre>
 * # 1. python3 /tmp/.../mock_openai_full.py &amp;  (listens on 127.0.0.1:9999)
 * # 2.
 * DB_URL=... DB_USER=... DB_PASSWORD=... \
 * AI_ENABLED=true AI_API_URL=http://127.0.0.1:9999/v1/chat/completions AI_API_KEY=verify-test-key \
 *   mvn -pl vyoog-api -am test -Dtest=LlmAdjudicatorVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class LlmAdjudicatorVerificationRunner extends VerificationRunnerBase {

    @Autowired LlmAdjudicator adjudicator;

    @Test
    void adjudicatesARealContradictionAgainstARealHttpCallAndRefusesWhatItShouldRefuse() {
        var contradiction = adjudicator.adjudicate(
            "The search endpoint must run in under 100ms.", "The search endpoint must run in under 5 seconds.");
        System.out.println("[verify] contradicts=" + contradiction.contradicts()
            + " confidence=" + contradiction.confidence() + " explanation=" + contradiction.explanation());
        assertThat(contradiction.contradicts()).isTrue();
        assertThat(contradiction.explanation()).isNotBlank();

        var noConflict = adjudicator.adjudicate(
            "The system shall log every login attempt.", "The system shall export data as CSV.");
        assertThat(noConflict.contradicts()).isFalse();

        assertThat(adjudicator.modelAndPromptVersion()).contains("conflict-adjudication-v1");

        // The provider returning an HTTP error surfaces as AiProviderUnavailableException, not a fabricated verdict.
        assertThatThrownBy(() -> adjudicator.adjudicate("TRIGGER_ERROR", "irrelevant"))
            .isInstanceOf(AiProviderUnavailableException.class);
    }
}
