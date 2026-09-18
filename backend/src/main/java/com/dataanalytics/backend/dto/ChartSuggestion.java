package com.dataanalytics.backend.dto;

/**
 * A dashboard chart suggestion produced from analysis signals.
 *
 * Cross-column charts pair a categorical xColumn with a numeric yColumn and
 * an aggregation; single-column charts leave yColumn null.
 */
public record ChartSuggestion(
        String id,
        String type,
        String title,
        String xColumn,
        String yColumn,
        String aggregation,
        boolean featured,
        double relevanceScore,
        boolean crossColumn,
        Double correlation) {

    /** Convenience constructor for callers that do not score suggestions. */
    public ChartSuggestion(
            String id, String type, String title,
            String xColumn, String yColumn, String aggregation) {
        this(id, type, title, xColumn, yColumn, aggregation, false, 0.0, false, null);
    }
}
