package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A significant pairwise Pearson correlation between two numeric columns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsColumnCorrelation(
        String columnA,
        String columnB,
        double correlation,
        String strength,
        String direction,
        int sampleSize,
        boolean lowConfidence) {
}
