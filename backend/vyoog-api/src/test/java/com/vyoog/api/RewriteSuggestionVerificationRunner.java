package com.vyoog.api;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.RequirementRewriteAdvisor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VYB-0794: {@link com.vyoog.ai.OpenAiRequirementRewriteAdvisor} proven against a
 * real HTTP round trip to a throwaway local server standing in for OpenAI's Chat
 * Completions endpoint — same technique as
 * {@code ImportAiClassificationVerificationRunner}. Same explicit-name-only
 * convention: invisible to {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * # 1. python3 /tmp/.../mock_openai_rewrite.py &amp;  (listens on 127.0.0.1:9998)
 * # 2.
 * DB_URL=... DB_USER=... DB_PASSWORD=... \
 * AI_ENABLED=true AI_API_URL=http://127.0.0.1:9998/v1/chat/completions AI_API_KEY=verify-test-key \
 *   mvn -pl vyoog-api -am test -Dtest=RewriteSuggestionVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class RewriteSuggestionVerificationRunner extends VerificationRunnerBase {

    @Autowired RequirementRewriteAdvisor advisor;

    @Test
    void suggestsARealRewriteAgainstARealHttpCallAndRefusesWhatItShouldRefuse() {
        var suggestion = advisor.suggest("The system shall be fast.", Map.of("wording", -10));
        System.out.println("[verify] rewritten=" + suggestion.rewrittenStatement() + " changes=" + suggestion.changes());
        assertThat(suggestion.rewrittenStatement()).contains("2 seconds");
        assertThat(suggestion.changes()).isNotEmpty();

        // The provider returning an HTTP error surfaces as AiProviderUnavailableException, not a fabricated guess.
        assertThatThrownBy(() -> advisor.suggest("TRIGGER_ERROR", Map.of()))
            .isInstanceOf(AiProviderUnavailableException.class);

        // A response the model itself left empty is refused, not silently accepted.
        assertThatThrownBy(() -> advisor.suggest("TRIGGER_EMPTY_REWRITE", Map.of()))
            .isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("did not return a rewritten statement");

        // An empty statement is refused before ever calling the provider.
        assertThatThrownBy(() -> advisor.suggest("", Map.of()))
            .isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("Nothing to rewrite");
    }
}
