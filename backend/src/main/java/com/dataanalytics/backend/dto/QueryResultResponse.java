package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Mirrors the JSON returned by FastAPI POST /query/execute (sandboxed DuckDB
 * execution). Semantic problems arrive as success=false + an error message
 * rather than HTTP errors, so they can be relayed into the conversation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryResultResponse(
        boolean success,
        List<String> columns,
        List<List<Object>> rows,
        Integer rowCount,
        String error) {
}
