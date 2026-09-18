package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors the JSON returned by FastAPI /insights/period-comparison. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsPeriodComparisonResponse(
        String periodType,
        String previousLabel,
        String currentLabel,
        List<AnalyticsColumnComparison> comparisons,
        List<String> skippedReasons) {
}
