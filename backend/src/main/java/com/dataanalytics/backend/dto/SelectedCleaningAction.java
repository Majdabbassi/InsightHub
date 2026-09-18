package com.dataanalytics.backend.dto;

import jakarta.validation.constraints.NotNull;

/** A user-selected cleaning action sent by the frontend. */
public record SelectedCleaningAction(
        String columnName,
        @NotNull CleaningActionType actionType,
        String customValue) {

    public enum CleaningActionType {
        DROP_ROWS,
        DROP_DUPLICATES,
        FILL_MEAN,
        FILL_MEDIAN,
        FILL_ZERO,
        FILL_MODE,
        FILL_CUSTOM_VALUE,
        COERCE_TYPE,
        DROP_INVALID_ROWS,
        CAP_TO_BOUNDS,
        REMOVE_ROWS,
        SET_TO_ZERO,
        FLAG_ONLY,
        NONE
    }
}
