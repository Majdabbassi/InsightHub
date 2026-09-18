package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.OllamaChatDtos;
import com.dataanalytics.backend.dto.OllamaChatDtos.OllamaChatRequest;
import com.dataanalytics.backend.dto.OllamaChatDtos.OllamaChatResponse;
import com.dataanalytics.backend.dto.OllamaChatDtos.OllamaMessage;
import com.dataanalytics.backend.exception.AssistantUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Thin client for a local Ollama server (POST /api/chat, non-streaming).
 * Any connectivity/timeout/server failure maps to AssistantUnavailableException
 * so the API can answer with a clean 503 instead of leaking infra details.
 */
@Service
@Slf4j
public class OllamaClient {

    private final RestClient restClient;
    private final String model;

    public OllamaClient(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.model}") String model,
            @Value("${ollama.timeout-seconds}") long timeoutSeconds) {
        this.model = model;

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        // Local LLMs can be slow, especially on first load or CPU-only machines.
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** Keeps the model warm between questions so chats don't pay reload cost. */
    private static final String KEEP_ALIVE = "30m";

    /**
     * Bounded context/output so CPU inference stays fast and memory sane.
     * Low temperature: answers must follow strict formats (JSON query
     * actions) and stay factual rather than creative.
     */
    private static final OllamaChatDtos.OllamaOptions OPTIONS =
            new OllamaChatDtos.OllamaOptions(8192, 1024, 0.2);

    /** Instant transport failures (dead pooled connections) get a few fast retries. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 300;

    /**
     * Sends the full message list (system prompt + context + history + new
     * question) and returns the assistant's reply text.
     */
    public String chat(List<OllamaMessage> messages) {
        ResourceAccessException transportFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                OllamaChatResponse response = restClient.post()
                        .uri("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new OllamaChatRequest(model, false, KEEP_ALIVE, OPTIONS, messages))
                        .retrieve()
                        .body(OllamaChatResponse.class);
                if (response == null || response.message() == null) {
                    throw new AssistantUnavailableException("Ollama returned no message.");
                }
                return response.message().content();
            } catch (ResourceAccessException e) {
                if (e.getCause() instanceof java.net.http.HttpTimeoutException) {
                    // Real read timeout (slow generation): retrying adds minutes.
                    throw unavailable(e);
                }
                transportFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw unavailable(e);
                    }
                }
            } catch (RestClientException e) {
                throw unavailable(e);
            }
        }
        throw unavailable(transportFailure);
    }

    private AssistantUnavailableException unavailable(Exception cause) {
        log.warn("Ollama call failed: {}", cause == null ? "" : cause.getMessage());
        return new AssistantUnavailableException(
                "The AI assistant is currently unavailable - please make sure "
                        + "Ollama is running and the model is pulled.", cause);
    }
}
