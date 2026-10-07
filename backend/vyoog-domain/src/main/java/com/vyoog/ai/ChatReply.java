package com.vyoog.ai;

/**
 * VYB-0936: what came back from a chat completion. Token counts are what the provider reported, or null when it
 * did not say (they are not estimated). Recording them is VYB-0939; this row only carries them.
 */
public record ChatReply(String content, String model, String finishReason, Integer promptTokens, Integer completionTokens) {}
