package com.dataanalytics.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Project-level aggregate statistics with lineage-aware counting.
 * activeStats counts only datasets that are not superseded by a cleaned
 * derivative; allVersionsStats counts everything.
 */
public record ProjectOverviewResponse(
        Stats activeStats,
        Stats allVersionsStats,
        List<ProjectDatasetSummary> datasets) {

    public record Stats(
            int datasetCount,
            long totalRows,
            long totalColumns,
            double averageQualityScore,
            QualityGradeBreakdown qualityGradeBreakdown,
            int lowQualityDatasetCount) {
    }

    /** Counts per grade; every grade key is always present (0 when unused). */
    public record QualityGradeBreakdown(int A, int B, int C, int D, int F) {
    }

    public record ProjectDatasetSummary(
            Long id,
            String name,
            Integer rowCount,
            Integer columnCount,
            Integer qualityScore,
            String qualityGrade,
            LocalDateTime uploadedAt,
            boolean isActive,
            Long sourceDatasetId,
            boolean isCleanedVersion) {
    }
}
