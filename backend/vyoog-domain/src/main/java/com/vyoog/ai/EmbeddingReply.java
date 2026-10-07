package com.vyoog.ai;

/** VYB-0936: an embedding and what the provider reported about it. */
public record EmbeddingReply(float[] vector, String model, Integer promptTokens) {}
