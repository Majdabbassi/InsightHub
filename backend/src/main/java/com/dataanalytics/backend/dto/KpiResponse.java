package com.dataanalytics.backend.dto;

public record KpiResponse(
        long totalRows,
        int totalColumns,
        double missingValuesPercent,
        double duplicateRowsPercent) {
}
