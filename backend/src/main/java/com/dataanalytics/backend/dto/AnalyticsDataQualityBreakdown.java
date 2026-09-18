package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Per-component quality scores, each between 0.0 and 1.0. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsDataQualityBreakdown(
        double completeness,
        double uniqueness,
        double consistency,
        double validity) {
}
