package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Dataset-level composite quality score aggregated by the analytics service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsDataQuality(
        int overallScore,
        String grade,
        AnalyticsDataQualityBreakdown breakdown) {
}
