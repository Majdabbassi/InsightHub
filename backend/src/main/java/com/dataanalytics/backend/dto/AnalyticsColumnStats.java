package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsColumnStats(
        String name,
        String dataType,
        String semanticRole,
        double confidence,
        String reasoning,
        int missingCount,
        double missingPercentage,
        int uniqueCount,
        int invalidValueCount,
        Double min,
        Double max,
        Double mean,
        Double median,
        Double stdDev,
        List<AnalyticsTopValue> topValues,
        AnalyticsOutlierAnalysis outlierAnalysis) {
}
