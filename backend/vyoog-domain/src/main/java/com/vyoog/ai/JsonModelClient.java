package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VYB-0667, moved onto the gateway by VYB-0936: one chat call that returns a JSON object, shared by the
 * document-analysis agents, brief analysis, elaboration and test-case suggestion. It builds no request of its
 * own; {@link ModelGateway} sends it, with the gateway's retries and breaker. This class asks for a JSON reply,
 * refuses one cut off at the token limit, and parses it.
 */
@Component
public class JsonModelClient {

    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final int timeoutSeconds;

    /**
     * @param timeoutSeconds one attempt's limit. Document analysis reads far more text per call than a single-statement
     *     classifier, so it has its own setting: timing out a long synthesis would look identical to a provider outage.
     */
    public JsonModelClient(ModelGateway gateway, ObjectMapper json,
                           @Value("${vyoog.ai.analysis-timeout-seconds:90}") int timeoutSeconds) {
        this.gateway = gateway;
        this.json = json;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String model() {
        return gateway.chatModel();
    }

    public boolean configured() {
        return gateway.configured();
    }

    /**
     * @param purpose what this call is for, recorded in the usage ledger (VYB-0939)
     * @param maxTokens hard cap on the reply; a truncated reply is half a JSON document, so it surfaces as an
     *                  {@link AiProviderUnavailableException} rather than as a half-read result.
     * @return the parsed JSON object the model was told to return
     */
    public JsonNode completeJson(String purpose, String systemPrompt, String userContent, int maxTokens, double temperature) {
        ChatReply reply = gateway.chat(
            ChatRequest.batchJson(purpose, systemPrompt, userContent, maxTokens, temperature, Duration.ofSeconds(timeoutSeconds)));
        if ("length".equals(reply.finishReason())) {
            throw new AiProviderUnavailableException("AI provider's reply was cut off at the token limit before it finished.");
        }
        try {
            return json.readTree(reply.content());
        } catch (Exception e) {
            throw new AiProviderUnavailableException("AI provider's response could not be parsed: " + e.getMessage());
        }
    }
}
