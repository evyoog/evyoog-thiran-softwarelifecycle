package com.vyoog.ai;

/**
 * VYB-0603: an AI provider — embeddings or an LLM adjudicator — couldn't be reached.
 * Never faked as a zero vector, an empty verdict, or any other stand-in for "unknown."
 */
public class AiProviderUnavailableException extends RuntimeException {
    public AiProviderUnavailableException(String message) {
        super(message);
    }

    public AiProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
