package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirrors one detected trend from the FastAPI /insights/trends endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsTrendInsight(
        String column,
        String temporalColumn,
        String periodGrouping,
        int periodCount,
        String direction,
        String strength,
        double rSquared,
        Double percentageChange,
        String firstPeriodLabel,
        String lastPeriodLabel,
        List<Double> points,
        List<String> pointLabels,
        String summary) {
}
