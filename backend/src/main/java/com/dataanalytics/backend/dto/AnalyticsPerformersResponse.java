package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors the JSON returned by FastAPI /insights/top-bottom-performers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsPerformersResponse(
        List<AnalyticsPerformersPair> performers,
        List<String> skippedReasons) {
}
