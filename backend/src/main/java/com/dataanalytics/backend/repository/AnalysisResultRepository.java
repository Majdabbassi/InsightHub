package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    Optional<AnalysisResult> findByDatasetId(Long datasetId);
}
