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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A link between two datasets of a project. FOREIGN_KEY relationships point
 * child -> parent (datasetA repeats values that datasetB holds uniquely, like
 * order_id in order_items referencing orders); SIBLING relationships connect
 * datasets with near-identical schemas (different slices of the same report).
 * MANUAL relationships keep whatever orientation the user entered.
 */
@Entity
@Table(name = "dataset_relationships")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetRelationship {

    public enum RelationshipStatus {
        SUGGESTED,
        CONFIRMED,
        REJECTED,
        MANUAL
    }

    /** How the link was established: a key reference or a schema twin. */
    public enum RelationshipType {
        FOREIGN_KEY,
        SIBLING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Child / FK side for detected relationships. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_a_id", nullable = false)
    private Dataset datasetA;

    /** Referenced / parent side for detected relationships. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_b_id", nullable = false)
    private Dataset datasetB;

    /** Column on datasetA carrying the shared values. */
    @Column(name = "shared_column_a", nullable = false, length = 255)
    private String sharedColumnA;

    /**
     * Column on datasetB; usually mirrors sharedColumnA but may differ
     * (e.g. "order_id" vs "orderId").
     */
    @Column(name = "shared_column_b", nullable = false, length = 255)
    private String sharedColumnB;

    /** Share of the child column's values found in the parent column (0-100). */
    @Column(name = "match_percentage")
    private Double matchPercentage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RelationshipStatus status;

    /**
     * FOREIGN_KEY for the pre-existing detected/manual links, SIBLING for
     * schema-twin pairs. The DB default backfills rows created before this
     * field existed.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) DEFAULT 'FOREIGN_KEY'")
    private RelationshipType relationshipType = RelationshipType.FOREIGN_KEY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
