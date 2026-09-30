package com.vyoog.api;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.TestCaseGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VYB-0824: {@link com.vyoog.ai.OpenAiTestCaseGenerator} proven against a real HTTP
 * round trip to a throwaway local server standing in for OpenAI's Chat Completions
 * endpoint — same technique as {@code RewriteSuggestionVerificationRunner}. Same
 * explicit-name-only convention: invisible to {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * # 1. start a mock server on 127.0.0.1:9998 that returns a real testCases array for a
 * #    normal request, and simulates a provider error for "TRIGGER_ERROR".
 * # 2.
 * DB_URL=... DB_USER=... DB_PASSWORD=... \
 * AI_ENABLED=true AI_API_URL=http://127.0.0.1:9998/v1/chat/completions AI_API_KEY=verify-test-key \
 *   mvn -pl vyoog-api -am test -Dtest=AiTestCaseSuggestionVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class AiTestCaseSuggestionVerificationRunner extends VerificationRunnerBase {

    @Autowired TestCaseGenerator generator;

    @Test
    void generatesCategorizedSuggestionsAgainstARealHttpCallAndRefusesWhatItShouldRefuse() {
        var requirement = new TestCaseGenerator.RequirementInput(
            "VY-1", "Lead capture", "The system shall capture a lead.", List.of("A valid email is required."), List.of());

        var withoutRelated = generator.generate(requirement, List.of());
        System.out.println("[verify] no related requirements, no linked commits -> " + withoutRelated);
        assertThat(withoutRelated).isNotEmpty();
        assertThat(withoutRelated).allMatch(s -> s.category() == TestCaseGenerator.Category.INDIVIDUAL);

        var related = List.of(new TestCaseGenerator.RelatedRequirement(
            "VY-2", "Lead routing", "The system shall route a captured lead to a queue.", "DOWNSTREAM"));
        var withRelated = generator.generate(requirement, related);
        System.out.println("[verify] with a downstream related requirement -> " + withRelated);
        assertThat(withRelated).anyMatch(s -> s.category() == TestCaseGenerator.Category.DEPENDENCY);

        // VYB-0829: linked commit messages are the only "existing code" signal this
        // platform has — proven here against a real HTTP round trip, not just unit-mocked.
        var withCommits = new TestCaseGenerator.RequirementInput("VY-1", "Lead capture",
            "The system shall capture a lead.", List.of("A valid email is required."),
            List.of("Capture lead email field\n\nRequirement: VY-1"));
        var regressionAware = generator.generate(withCommits, List.of());
        System.out.println("[verify] with a linked commit message -> " + regressionAware);
        assertThat(regressionAware).isNotEmpty();

        // The provider returning an HTTP error surfaces as AiProviderUnavailableException, not a fabricated guess.
        var errorRequirement = new TestCaseGenerator.RequirementInput("VY-X", "x", "TRIGGER_ERROR", List.of(), List.of());
        assertThatThrownBy(() -> generator.generate(errorRequirement, List.of()))
            .isInstanceOf(AiProviderUnavailableException.class);
    }
}
