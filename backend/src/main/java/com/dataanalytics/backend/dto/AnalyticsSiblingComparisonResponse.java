package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors the JSON returned by FastAPI /insights/sibling-comparison. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsSiblingComparisonResponse(
        String comparisonType,
        String datasetALabel,
        String datasetBLabel,
        List<AnalyticsColumnComparison> comparisons,
        List<String> skippedReasons) {
}
