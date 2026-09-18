package com.dataanalytics.backend.dto;

/** Result of a relationship (re-)scan. */
public record ScanResponse(
        int scannedPairs,
        int createdRelationships) {
}
