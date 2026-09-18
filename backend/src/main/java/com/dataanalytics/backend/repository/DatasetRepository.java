package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.Dataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetRepository extends JpaRepository<Dataset, Long> {

    List<Dataset> findByProjectId(Long projectId);
}
