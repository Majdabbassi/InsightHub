package com.dataanalytics.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Last computed result of one Insights type for one dataset (cache-aside
 * persistence of the FastAPI responses). Freshness is maintained by explicit
 * invalidation: re-analysing or cleaning a dataset deletes its snapshots, so
 * any row that still exists was computed from the current data.
 */
@Entity
@Table(name = "dataset_insight_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_insight_snapshot_dataset_type",
                columnNames = {"dataset_id", "insight_type"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetInsightSnapshot {

    public enum InsightType {
        TREND,
        PERIOD_COMPARISON,
        ANOMALY,
        TOP_BOTTOM_PERFORMERS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false)
    private Dataset dataset;

    @Enumerated(EnumType.STRING)
    @Column(name = "insight_type", nullable = false, length = 30)
    private InsightType insightType;

    /** Raw JSON of the FastAPI response this snapshot caches. */
    @Lob
    @Column(name = "result_json", columnDefinition = "TEXT", nullable = false)
    private String resultJson;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @PrePersist
    protected void onCreate() {
        if (computedAt == null) {
            computedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        computedAt = LocalDateTime.now();
    }
}
