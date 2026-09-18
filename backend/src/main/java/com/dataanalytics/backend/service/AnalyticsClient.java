package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsAnalysisResponse;
import com.dataanalytics.backend.dto.AnalyticsAnomaliesResponse;
import com.dataanalytics.backend.dto.AnalyticsCleaningSuggestion;
import com.dataanalytics.backend.dto.AnalyticsCleaningSuggestionsResponse;
import com.dataanalytics.backend.dto.AnalyticsCleaningSummary;
import com.dataanalytics.backend.dto.AnalyticsPeriodComparisonResponse;
import com.dataanalytics.backend.dto.AnalyticsPerformersResponse;
import com.dataanalytics.backend.dto.AnalyticsRelationalInsightResponse;
import com.dataanalytics.backend.dto.AnalyticsSiblingComparisonResponse;
import com.dataanalytics.backend.dto.AnalyticsTrendInsightsResponse;
import com.dataanalytics.backend.dto.QueryResultResponse;
import com.dataanalytics.backend.dto.SelectedCleaningAction;
import com.dataanalytics.backend.exception.AnalysisFailedException;
import com.dataanalytics.backend.exception.AnalyticsServiceUnavailableException;
import com.dataanalytics.backend.exception.CleaningFailedException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client for the FastAPI analytics microservice. Forwards stored dataset
 * files to POST /analyze and maps failures to clean backend exceptions.
 * Transient failures (connection errors and HTTP 5xx) are retried with
 * exponential backoff; client errors (4xx) are never retried.
 */
@Service
@Slf4j
public class AnalyticsClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final long backoffMillis;

    public AnalyticsClient(@Value("${analytics.service.url}") String baseUrl,
                           @Value("${analytics.client.retry.max-attempts:3}") int maxAttempts,
                           @Value("${analytics.client.retry.backoff-millis:500}") long backoffMillis,
                           ObjectMapper objectMapper) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build());
        requestFactory.setReadTimeout(READ_TIMEOUT);

        // Force HTTP/1.1: the JDK client otherwise sends an h2c "Upgrade"
        // handshake that uvicorn mishandles, dropping the multipart body.
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    /**
     * Executes an analytics call, retrying only transient failures: transport
     * errors ({@link ResourceAccessException}) and HTTP 5xx responses. Each
     * retry waits {@code backoffMillis * 2^(attempt-1)} before the next call
     * and rethrows the last failure once {@code maxAttempts} are exhausted.
     * HTTP 4xx responses are rethrown immediately (they are contract errors,
     * not transient failures).
     */
    private <T> T withRetry(Supplier<T> call) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() < 500) {
                    throw e;
                }
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("Analytics service returned {} (attempt {} of {}); retrying",
                        e.getStatusCode().value(), attempt, maxAttempts);
            } catch (ResourceAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("Analytics service unreachable ({}/{}); retrying",
                        attempt, maxAttempts);
            }
            sleepBackoff(attempt);
        }
    }

    private void sleepBackoff(int attempt) {
        long delay = Math.min(backoffMillis * (1L << (attempt - 1)), 10_000L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalyticsServiceUnavailableException(
                    "Analytics call interrupted while waiting to retry.");
        }
    }

    public AnalyticsAnalysisResponse analyze(Path filePath, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(filePath) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        try {
            AnalyticsAnalysisResponse response = withRetry(() -> restClient.post()
                    .uri("/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsAnalysisResponse.class));

            if (response == null) {
                throw new AnalysisFailedException("Analytics service returned an empty response");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Analysis failed: " + e.getMessage());
        }
    }

    /**
     * FastAPI errors look like {"detail": "..."} (or an array of validation
     * errors) — surface that message to the user.
     */
    private String extractDetail(RestClientResponseException e) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            if (node.has("detail")) {
                JsonNode detail = node.get("detail");
                String text = detail.isContainer() ? detail.toString() : detail.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        } catch (Exception ignored) {
            // fall through to generic message
        }
        return "Analysis failed (analytics service responded with status "
                + e.getStatusCode().value() + ").";
    }

    /** Result of a cleaning apply: cleaned CSV bytes + parsed summary header. */
    public record CleaningApplyResult(byte[] csvBytes, AnalyticsCleaningSummary summary) {
    }

    /**
     * Rule-based trend detection: forwards the dataset file to FastAPI
     * POST /insights/trends and returns the detected trends as-is.
     */
    public AnalyticsTrendInsightsResponse trends(Path filePath, String filename) {
        MultiValueMap<String, Object> body = fileBody(filePath, filename);
        try {
            AnalyticsTrendInsightsResponse response = withRetry(() -> restClient.post()
                    .uri("/insights/trends")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsTrendInsightsResponse.class));

            if (response == null) {
                throw new AnalysisFailedException(
                        "Analytics service returned an empty trend response");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Trend detection failed: " + e.getMessage());
        }
    }

    /**
     * Period-level anomaly detection: forwards the dataset file to FastAPI
     * POST /insights/anomalies and returns the detected anomalies as-is.
     */
    public AnalyticsAnomaliesResponse anomalies(Path filePath, String filename) {
        MultiValueMap<String, Object> body = fileBody(filePath, filename);
        try {
            AnalyticsAnomaliesResponse response = withRetry(() -> restClient.post()
                    .uri("/insights/anomalies")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsAnomaliesResponse.class));

            if (response == null) {
                throw new AnalysisFailedException(
                        "Analytics service returned an empty anomaly response");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Anomaly detection failed: " + e.getMessage());
        }
    }

    /**
     * Top/bottom performer ranking: forwards the dataset file to FastAPI
     * POST /insights/top-bottom-performers and returns the rankings as-is.
     */
    public AnalyticsPerformersResponse performers(Path filePath, String filename) {
        MultiValueMap<String, Object> body = fileBody(filePath, filename);
        try {
            AnalyticsPerformersResponse response = withRetry(() -> restClient.post()
                    .uri("/insights/top-bottom-performers")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsPerformersResponse.class));

            if (response == null) {
                throw new AnalysisFailedException(
                        "Analytics service returned an empty performers response");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Performer ranking failed: " + e.getMessage());
        }
    }

    /**
     * Recent-vs-previous period comparison. Optional overrides are appended
     * as query params only when present, matching FastAPI's Optional params.
     */
    public AnalyticsPeriodComparisonResponse periodComparison(
            Path filePath, String filename, String periodType,
            String customCurrentStart, String customCurrentEnd,
            String customPreviousStart, String customPreviousEnd) {
        MultiValueMap<String, Object> body = fileBody(filePath, filename);
        try {
            AnalyticsPeriodComparisonResponse response = withRetry(() -> restClient.post()
                    .uri(builder -> {
                        builder.path("/insights/period-comparison");
                        if (periodType != null && !periodType.isBlank()) {
                            builder.queryParam("periodType", periodType);
                        }
                        if (customCurrentStart != null && !customCurrentStart.isBlank()) {
                            builder.queryParam("customCurrentStart", customCurrentStart);
                        }
                        if (customCurrentEnd != null && !customCurrentEnd.isBlank()) {
                            builder.queryParam("customCurrentEnd", customCurrentEnd);
                        }
                        if (customPreviousStart != null && !customPreviousStart.isBlank()) {
                            builder.queryParam("customPreviousStart", customPreviousStart);
                        }
                        if (customPreviousEnd != null && !customPreviousEnd.isBlank()) {
                            builder.queryParam("customPreviousEnd", customPreviousEnd);
                        }
                        return builder.build();
                    })
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsPeriodComparisonResponse.class));

            if (response == null) {
                throw new AnalysisFailedException(
                        "Analytics service returned an empty comparison response");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Period comparison failed: " + e.getMessage());
        }
    }

    /**
     * Sibling comparison: sends both dataset files plus their already-stored
     * analysis summaries (optional) to FastAPI POST /insights/sibling-comparison.
     */
    public AnalyticsSiblingComparisonResponse siblingComparison(
            Path fileA, String filenameA, String labelA,
            Path fileB, String filenameB, String labelB,
            String analysisJsonA, String analysisJsonB) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("datasetA", namedFile(fileA, filenameA));
        body.add("datasetB", namedFile(fileB, filenameB));
        addTextField(body, "datasetALabel", labelA);
        addTextField(body, "datasetBLabel", labelB);
        addTextField(body, "analysisA", analysisJsonA);
        addTextField(body, "analysisB", analysisJsonB);
        try {
            AnalyticsSiblingComparisonResponse response = withRetry(() -> restClient.post()
                    .uri("/insights/sibling-comparison")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsSiblingComparisonResponse.class));
            if (response == null) {
                throw new AnalysisFailedException(
                        "Analytics service returned an empty sibling comparison");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Sibling comparison failed: " + e.getMessage());
        }
    }

    /**
     * Relational insights for one confirmed foreign-key relationship: sends
     * parent/child files plus shared columns to FastAPI POST /insights/relational.
     */
    public AnalyticsRelationalInsightResponse relationalInsights(
            Path parentFile, String parentFilename,
            Path childFile, String childFilename,
            String parentColumn, String childColumn,
            String matchPercentage, String analysisParentJson, String analysisChildJson) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("parentFile", namedFile(parentFile, parentFilename));
        body.add("childFile", namedFile(childFile, childFilename));
        addTextField(body, "parentColumn", parentColumn);
        addTextField(body, "childColumn", childColumn);
        addTextField(body, "matchPercentage", matchPercentage);
        addTextField(body, "analysisParent", analysisParentJson);
        addTextField(body, "analysisChild", analysisChildJson);
        try {
            AnalyticsRelationalInsightResponse response = withRetry(() -> restClient.post()
                    .uri("/insights/relational")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsRelationalInsightResponse.class));
            if (response == null) {
                throw new AnalysisFailedException(
                        "Analytics service returned an empty relational insight");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Relational insights failed: " + e.getMessage());
        }
    }

    private static FileSystemResource namedFile(Path filePath, String filename) {
        return new FileSystemResource(filePath) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /**
     * Executes a read-only SQL query over the given dataset files in the
     * analytics service's sandboxed DuckDB (POST /query/execute). The map is
     * sanitized table name -> stored file path; files are sent under their
     * original names and matched through tablesJson. Semantic problems come
     * back as success=false payloads, not exceptions.
     */
    public QueryResultResponse executeQuery(
            String sql, java.util.LinkedHashMap<String, Path> tables,
            java.util.Map<String, String> sanitizedToOriginalFilename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        addTextField(body, "sql", sql);
        addTextField(body, "tablesJson",
                objectMapper.writeValueAsString(sanitizedToOriginalFilename));
        tables.forEach((sanitized, path) -> body.add("files",
                namedFile(path, sanitizedToOriginalFilename.get(sanitized))));
        try {
            QueryResultResponse response = withRetry(() -> restClient.post()
                    .uri("/query/execute")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(QueryResultResponse.class));
            if (response == null) {
                return new QueryResultResponse(false, null, null, null,
                        "Analytics service returned an empty response.");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            return new QueryResultResponse(false, null, null, null,
                    "Query execution rejected: " + extractDetail(e));
        } catch (RestClientException e) {
            return new QueryResultResponse(false, null, null, null,
                    "Query execution failed: " + e.getMessage());
        }
    }

    private static void addTextField(MultiValueMap<String, Object> body, String name, String value) {
        if (value != null && !value.isBlank()) {
            body.add(name, value);
        }
    }

    public List<AnalyticsCleaningSuggestion> cleaningSuggestions(Path filePath, String filename) {        MultiValueMap<String, Object> body = fileBody(filePath, filename);
        try {
            AnalyticsCleaningSuggestionsResponse response = withRetry(() -> restClient.post()
                    .uri("/clean/suggestions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalyticsCleaningSuggestionsResponse.class));

            return response == null || response.suggestions() == null
                    ? List.of()
                    : response.suggestions();
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new AnalysisFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new AnalysisFailedException("Could not fetch cleaning suggestions: " + e.getMessage());
        }
    }

    public CleaningApplyResult applyCleaning(
            Path filePath, String filename, List<SelectedCleaningAction> actions) {
        MultiValueMap<String, Object> body = fileBody(filePath, filename);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("actions", new HttpEntity<>(toJson(actions), jsonHeaders));

        try {
            return withRetry(() -> restClient.post()
                    .uri("/clean/apply")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new RestClientResponseException(
                                    "Analytics service error",
                                    response.getStatusCode().value(),
                                    response.getStatusText(),
                                    response.getHeaders(),
                                    response.getBody().readAllBytes(),
                                    null);
                        }
                        byte[] bytes = response.getBody().readAllBytes();
                        String rawSummary =
                                response.getHeaders().getFirst("X-Cleaning-Summary");
                        AnalyticsCleaningSummary summary = parseSummary(rawSummary);
                        return new CleaningApplyResult(bytes, summary);
                    }));
        } catch (ResourceAccessException e) {
            throw new AnalyticsServiceUnavailableException(
                    "Analytics service is unavailable. Please try again later.");
        } catch (RestClientResponseException e) {
            throw new CleaningFailedException(extractDetail(e));
        } catch (RestClientException e) {
            throw new CleaningFailedException("Cleaning failed: " + e.getMessage());
        }
    }

    private MultiValueMap<String, Object> fileBody(Path filePath, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(filePath) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return body;
    }

    private AnalyticsCleaningSummary parseSummary(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new CleaningFailedException(
                    "Analytics service did not return a cleaning summary.");
        }
        try {
            return objectMapper.readValue(rawJson, AnalyticsCleaningSummary.class);
        } catch (Exception e) {
            throw new CleaningFailedException("Could not read the cleaning summary.", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new CleaningFailedException("Could not serialize cleaning actions", e);
        }
    }
}
