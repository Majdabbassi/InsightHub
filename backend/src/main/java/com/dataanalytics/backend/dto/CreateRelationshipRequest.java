package com.dataanalytics.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body for manually creating a relationship (status MANUAL). */
public record CreateRelationshipRequest(
        @NotNull Long datasetAId,
        @NotNull Long datasetBId,
        @NotBlank String sharedColumnA,
        @NotBlank String sharedColumnB) {
}
