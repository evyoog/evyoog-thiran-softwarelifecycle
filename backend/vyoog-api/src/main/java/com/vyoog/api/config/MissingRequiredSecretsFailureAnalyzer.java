package com.vyoog.api.config;

import com.vyoog.api.config.RequiredSecretsEnvironmentPostProcessor.MissingRequiredSecretsException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link MissingRequiredSecretsException} into Boot's readable "failed to start" block instead of a stack trace. */
public class MissingRequiredSecretsFailureAnalyzer extends AbstractFailureAnalyzer<MissingRequiredSecretsException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingRequiredSecretsException cause) {
        return new FailureAnalysis(
            "These required environment variables are not set: " + String.join(", ", cause.envVars()),
            "Set them in the environment or the secrets manager. For local development copy "
                + ".env.example to .env and fill in the local values (see backend/README.md). "
                + "Never put a real credential in a committed file (D22).",
            cause);
    }
}
