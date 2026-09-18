package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** IQR-based outlier statistics for a numeric column. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsOutlierAnalysis(
        int outlierCount,
        int mildCount,
        int extremeCount,
        double outlierPercentage,
        Double lowerBound,
        Double upperBound,
        List<AnalyticsOutlierSample> outlierSamples,
        boolean lowConfidence) {
}
