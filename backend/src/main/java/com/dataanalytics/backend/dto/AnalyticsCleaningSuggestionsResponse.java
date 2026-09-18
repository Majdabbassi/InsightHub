package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors the {suggestions, totalIssues} payload from FastAPI /clean/suggestions. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsCleaningSuggestionsResponse(
        List<AnalyticsCleaningSuggestion> suggestions,
        int totalIssues) {
}
