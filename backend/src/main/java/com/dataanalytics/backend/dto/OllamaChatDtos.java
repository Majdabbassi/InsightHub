package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Request/response bodies of Ollama's POST /api/chat endpoint. */
public final class OllamaChatDtos {

    private OllamaChatDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OllamaMessage(
            String role,
            String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OllamaMessageResponse(
            String role,
            String content) {
    }

    /**
     * Bounded generation parameters. Without an explicit num_ctx, recent
     * Ollama versions allocate the model's maximum context (128k for
     * llama3.2), which cripples CPU inference and memory usage. The low
     * temperature keeps exact-format answers (the JSON query action) stable.
     */
    public record OllamaOptions(
            @JsonProperty("num_ctx") int numCtx,
            @JsonProperty("num_predict") int numPredict,
            @JsonProperty("temperature") double temperature) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OllamaChatRequest(
            String model,
            boolean stream,
            String keepAlive,
            OllamaOptions options,
            java.util.List<OllamaMessage> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OllamaChatResponse(
            OllamaMessageResponse message) {
    }
}
