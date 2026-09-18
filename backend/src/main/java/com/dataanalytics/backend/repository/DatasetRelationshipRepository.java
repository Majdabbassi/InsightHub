package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.DatasetRelationship;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetRelationshipRepository extends JpaRepository<DatasetRelationship, Long> {

    List<DatasetRelationship> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
