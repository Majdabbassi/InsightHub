package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.DatasetInsightSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatasetInsightSnapshotRepository extends JpaRepository<DatasetInsightSnapshot, Long> {

    Optional<DatasetInsightSnapshot> findByDataset_IdAndInsightType(
            Long datasetId, DatasetInsightSnapshot.InsightType insightType);

    void deleteByDataset_Id(Long datasetId);
}
