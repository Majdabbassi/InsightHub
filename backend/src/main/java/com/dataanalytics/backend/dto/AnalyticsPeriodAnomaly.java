package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors one flagged period from FastAPI /insights/anomalies. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsPeriodAnomaly(
        String column,
        String temporalColumn,
        String periodGrouping,
        String periodLabel,
        Double actualValue,
        Double typicalValue,
        Double zScore,
        String severity,
        String direction,
        Double multiplier,
        List<AnalyticsAnomalyDriver> topDrivers,
        String summary) {
}
