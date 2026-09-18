package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors the JSON returned by the FastAPI /insights/trends endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsTrendInsightsResponse(
        List<AnalyticsTrendInsight> trends,
        List<String> skippedReasons) {
}
