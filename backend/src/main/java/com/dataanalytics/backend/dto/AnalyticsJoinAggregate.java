package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One predefined aggregate over the parent<-child join. Null fields mean no
 * eligible numeric column was found on the child side.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsJoinAggregate(
        double avgChildCountPerParent,
        String childNumericColumn,
        Double avgNumericSumPerParent,
        String summary) {
}
