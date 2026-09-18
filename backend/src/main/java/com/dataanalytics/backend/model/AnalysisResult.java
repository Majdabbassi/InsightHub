package com.dataanalytics.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false, unique = true)
    private Dataset dataset;

    @Column(name = "row_count", nullable = false)
    private Integer rowCount;

    @Column(name = "column_count", nullable = false)
    private Integer columnCount;

    @Column(name = "duplicate_row_count", nullable = false)
    private Integer duplicateRowCount;

    @Column(name = "total_missing_values", nullable = false)
    private Long totalMissingValues;

    /** Per-column statistics stored as a JSON array of column stat objects. */
    @Lob
    @Column(name = "column_stats", columnDefinition = "TEXT", nullable = false)
    private String columnStatsJson;

    /**
     * Significant numeric column correlations stored as a JSON array.
     * Nullable: analyses made before this field existed have no value.
     */
    @Lob
    @Column(name = "correlations_json", columnDefinition = "TEXT")
    private String correlationsJson;

    /**
     * Composite data quality score stored as a JSON object.
     * Nullable: analyses made before this field existed have no value.
     */
    @Lob
    @Column(name = "data_quality_json", columnDefinition = "TEXT")
    private String dataQualityJson;

    @Column(name = "analyzed_at", nullable = false, updatable = false)
    private LocalDateTime analyzedAt;

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
    }
}
