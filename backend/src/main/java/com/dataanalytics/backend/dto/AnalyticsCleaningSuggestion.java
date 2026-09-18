package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors a single suggestion from FastAPI /clean/suggestions. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsCleaningSuggestion(
        String id,
        String type,
        String columnName,
        String description,
        String suggestedAction,
        List<String> alternativeActions,
        long affectedRowCount,
        String reasoning) {
}
