package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsTopValue(
        String value,
        int count) {
}
