package com.dataanalytics.backend.dto;

import com.dataanalytics.backend.model.DatasetRelationship.RelationshipStatus;
import com.dataanalytics.backend.model.DatasetRelationship.RelationshipType;

import java.time.LocalDateTime;

public record RelationshipResponse(
        Long id,
        Long projectId,
        Long datasetAId,
        String datasetAName,
        Long datasetBId,
        String datasetBName,
        String sharedColumnA,
        String sharedColumnB,
        Double matchPercentage,
        RelationshipStatus status,
        RelationshipType relationshipType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
