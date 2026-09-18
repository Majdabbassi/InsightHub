package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.CleaningJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CleaningJobRepository extends JpaRepository<CleaningJob, Long> {

    List<CleaningJob> findBySourceDataset_ProjectIdOrderByCreatedAtAsc(Long projectId);
}
