package com.dataanalytics.backend.dto;

/** Response after a cleaning job completes: new dataset + before/after summary. */
public record CleanedDatasetResponse(
        Long jobId,
        Long sourceDatasetId,
        DatasetResponse cleanedDataset,
        int rowsBefore,
        int rowsAfter,
        int rowsRemoved,
        int valuesFilled) {
}
