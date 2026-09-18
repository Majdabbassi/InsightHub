package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Parsed X-Cleaning-Summary header from FastAPI /clean/apply. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsCleaningSummary(
        int rowsBefore,
        int rowsAfter,
        int rowsRemoved,
        int valuesFilled) {
}
