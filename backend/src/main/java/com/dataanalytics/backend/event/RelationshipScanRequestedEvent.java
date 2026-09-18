package com.dataanalytics.backend.event;

/**
 * Published when a dataset finishes analysing; triggers automatic
 * relationship detection after the analysis transaction commits.
 */
public record RelationshipScanRequestedEvent(Long projectId, Long datasetId) {
}
