package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors the JSON returned by the FastAPI /insights/anomalies endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsAnomaliesResponse(
        List<AnalyticsPeriodAnomaly> anomalies,
        List<String> skippedReasons) {
}
