package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors one (categorical, numeric) ranking pair from FastAPI. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsPerformersPair(
        String categoricalColumn,
        String numericColumn,
        String aggregation,
        List<AnalyticsPerformerEntry> topPerformers,
        List<AnalyticsPerformerEntry> bottomPerformers,
        Double gap,
        Boolean isDominant,
        Double dominantPercentage,
        String note,
        String summary) {
}
