package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsAnalysisResponse;
import com.dataanalytics.backend.dto.AnalysisResponse;
import com.dataanalytics.backend.event.RelationshipScanRequestedEvent;
import com.dataanalytics.backend.exception.AnalysisNotFoundException;
import com.dataanalytics.backend.exception.DatasetNotFoundException;
import com.dataanalytics.backend.model.AnalysisResult;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import com.dataanalytics.backend.repository.DatasetRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisResultRepository analysisResultRepository;
    private final DatasetRepository datasetRepository;
    private final DatasetService datasetService;
    private final ProjectService projectService;
    private final FileStorageService fileStorageService;
    private final AnalyticsClient analyticsClient;
    private final ObjectMapper objectMapper;
    private final ProjectContextCache projectContextCache;
    private final InsightsService insightsService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void createTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AnalysisResponse analyze(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);

        // The analytics round-trip can take 30-120s — run it OUTSIDE any
        // transaction so a database connection is never held during the HTTP
        // call (and a slow service cannot pin the connection pool).
        var storedFile = fileStorageService.resolveExisting(dataset.getStoredFilePath());
        AnalyticsAnalysisResponse result =
                analyticsClient.analyze(storedFile, dataset.getOriginalFilename());

        // Persist the result, refresh the dataset's row/column counts, and
        // publish the relationship-scan event inside ONE short transaction.
        // The AFTER_COMMIT listener only fires when the publish happens while
        // a transaction is active, so all three share the same commit boundary.
        return transactionTemplate.execute(status -> {
            Dataset managed = datasetRepository.findById(dataset.getId())
                    .orElseThrow(() -> new DatasetNotFoundException(datasetId));
            // Phase 3 left the dataset's row/column counts nullable — fill
            // them in now that we know the real shape.
            managed.setRowCount((int) result.rowCount());
            managed.setColumnCount(result.columnCount());

            AnalysisResult entity = analysisResultRepository.findByDatasetId(managed.getId())
                    .orElseGet(() -> AnalysisResult.builder().dataset(managed).build());

            entity.setRowCount((int) result.rowCount());
            entity.setColumnCount(result.columnCount());
            entity.setDuplicateRowCount((int) result.duplicateRowCount());
            entity.setTotalMissingValues(result.summary().totalMissingValues());
            entity.setColumnStatsJson(toJson(result.columns()));
            entity.setCorrelationsJson(
                    result.correlations() == null ? "[]" : toJson(result.correlations()));
            entity.setDataQualityJson(
                    result.dataQuality() == null ? null : toJson(result.dataQuality()));

            AnalysisResult saved = analysisResultRepository.save(entity);
            projectContextCache.invalidate(projectId);
            insightsService.invalidateDatasetSnapshots(managed.getId());

            // Follow-up relationship detection; runs async AFTER this transaction
            // commits so it never blocks or breaks the analysis response.
            applicationEventPublisher.publishEvent(
                    new RelationshipScanRequestedEvent(projectId, managed.getId()));

            return toResponse(saved, managed.getId());
        });
    }

    @Transactional(readOnly = true)
    public AnalysisResponse getAnalysis(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);

        AnalysisResult result = analysisResultRepository.findByDatasetId(dataset.getId())
                .orElseThrow(() -> new AnalysisNotFoundException(datasetId));

        return toResponse(result, dataset.getId());
    }

    private AnalysisResponse toResponse(AnalysisResult result, Long datasetId) {
        try {
            return new AnalysisResponse(
                    result.getId(),
                    datasetId,
                    result.getAnalyzedAt(),
                    result.getRowCount(),
                    result.getColumnCount(),
                    result.getDuplicateRowCount(),
                    result.getTotalMissingValues(),
                    objectMapper.readTree(result.getColumnStatsJson()),
                    readCorrelations(result.getCorrelationsJson()),
                    readNullable(result.getDataQualityJson()));
        } catch (Exception e) {
            throw new IllegalStateException("Stored analysis result is corrupted", e);
        }
    }

    /** Analyses from before correlations existed have no stored value. */
    private JsonNode readCorrelations(String correlationsJson) {
        if (correlationsJson == null || correlationsJson.isBlank()) {
            return objectMapper.readTree("[]");
        }
        JsonNode node = readNullable(correlationsJson);
        return node == null ? objectMapper.readTree("[]") : node;
    }

    /** Stored JSON that may be absent for analyses predating a given field. */
    private JsonNode readNullable(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode node = objectMapper.readTree(json);
        return node == null || node.isNull() ? null : node;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize analysis columns", e);
        }
    }
}
