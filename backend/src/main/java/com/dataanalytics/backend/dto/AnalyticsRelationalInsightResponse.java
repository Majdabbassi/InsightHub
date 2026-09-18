package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Mirrors the JSON returned by FastAPI /insights/relational. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsRelationalInsightResponse(
        AnalyticsReferentialCompleteness referentialCompleteness,
        AnalyticsJoinAggregate joinAggregate) {
}
