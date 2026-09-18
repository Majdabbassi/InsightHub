package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Mirrors a category driver of an anomalous period. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsAnomalyDriver(
        String categoryColumn,
        String categoryValue,
        Double periodValue,
        Double typicalValue) {
}
