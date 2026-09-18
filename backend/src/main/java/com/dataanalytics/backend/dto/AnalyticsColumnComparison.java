package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Recent-vs-previous period comparison for one numeric column. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsColumnComparison(
        String column,
        double previousValue,
        double currentValue,
        double delta,
        Double percentChange,
        boolean lowConfidence,
        List<AnalyticsComparisonDriver> topDrivers,
        String summary) {
}
