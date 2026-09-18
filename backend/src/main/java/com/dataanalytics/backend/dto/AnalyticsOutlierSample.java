package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One outlying value from IQR detection, traceable to its dataframe row. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsOutlierSample(
        double value,
        int rowIndex) {
}
