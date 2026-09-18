package com.dataanalytics.backend.exception;

/** Thrown when the dashboard is requested but the dataset has not been analyzed yet. */
public class DashboardRequiresAnalysisException extends RuntimeException {

    public DashboardRequiresAnalysisException(Long datasetId) {
        super("Analyze this dataset first to view the dashboard.");
    }
}
