package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors the JSON returned by the FastAPI /analyze endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsAnalysisResponse(
        long rowCount,
        int columnCount,
        long duplicateRowCount,
        List<AnalyticsColumnStats> columns,
        AnalyticsSummary summary,
        List<AnalyticsColumnCorrelation> correlations,
        AnalyticsDataQuality dataQuality) {
}
