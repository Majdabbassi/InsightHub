package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A category that moved most between the compared periods. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsComparisonDriver(
        String categoryColumn,
        String categoryValue,
        double previousValue,
        double currentValue,
        double delta) {
}
