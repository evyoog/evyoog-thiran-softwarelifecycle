package com.vyoog.api;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VYB-0602: {@link com.vyoog.ai.OpenAiEmbeddingProvider} proven against a real HTTP
 * round trip to a throwaway local server standing in for OpenAI's Embeddings
 * endpoint — the exact 1536-dimension width {@code requirement_embedding.embedding}
 * already declares, confirmed from the actual response rather than assumed. Same
 * explicit-name-only convention: invisible to {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * # 1. python3 /tmp/.../mock_openai_full.py &amp;  (listens on 127.0.0.1:9999)
 * # 2.
 * DB_URL=... DB_USER=... DB_PASSWORD=... \
 * AI_ENABLED=true AI_API_URL=http://127.0.0.1:9999/v1/chat/completions \
 * AI_EMBEDDINGS_URL=http://127.0.0.1:9999/v1/embeddings AI_API_KEY=verify-test-key \
 *   mvn -pl vyoog-api -am test -Dtest=EmbeddingProviderVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class EmbeddingProviderVerificationRunner {

    @Autowired EmbeddingProvider provider;

    @Test
    void embedsARealStatementAgainstARealHttpCallAtTheExactSchemaWidth() {
        float[] vector = provider.embed("The system shall encrypt data at rest.");
        System.out.println("[verify] modelName=" + provider.modelName()
            + " dimensions=" + provider.dimensions() + " actualLength=" + vector.length);
        assertThat(provider.dimensions()).isEqualTo(1536);
        assertThat(vector).hasSize(1536);
        assertThat(provider.modelName()).isEqualTo("text-embedding-3-small");

        // The provider returning an HTTP error surfaces as AiProviderUnavailableException, not a zero vector.
        assertThatThrownBy(() -> provider.embed("TRIGGER_ERROR"))
            .isInstanceOf(AiProviderUnavailableException.class);
    }
}
