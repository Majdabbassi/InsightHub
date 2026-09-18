package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Mirrors one ranked category entry from FastAPI /insights/top-bottom-performers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsPerformerEntry(
        String category,
        Double sumValue,
        Double avgValue,
        int rank) {
}
