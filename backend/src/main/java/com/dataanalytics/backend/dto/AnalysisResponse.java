package com.dataanalytics.backend.dto;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/** Analysis payload returned to the frontend. */
public record AnalysisResponse(
        Long id,
        Long datasetId,
        LocalDateTime analyzedAt,
        long rowCount,
        int columnCount,
        long duplicateRowCount,
        long totalMissingValues,
        JsonNode columns,
        JsonNode correlations,
        JsonNode dataQuality) {
}
