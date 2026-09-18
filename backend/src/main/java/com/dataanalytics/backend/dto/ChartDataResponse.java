package com.dataanalytics.backend.dto;

import java.util.List;

/**
 * Data for one chart. Most charts use the labels/values shape;
 * SCATTER charts use the paired points list instead.
 */
public record ChartDataResponse(
        List<String> labels,
        List<Number> values,
        List<Point> points) {

    public record Point(double x, double y) {
    }

    /** Convenience constructor for label/value charts. */
    public ChartDataResponse(List<String> labels, List<Number> values) {
        this(labels, values, null);
    }
}
