package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Referential-completeness finding for a confirmed foreign-key link. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsReferentialCompleteness(
        double matchPercentage,
        long mismatchCount,
        double mismatchPercentage,
        String summary) {
}
