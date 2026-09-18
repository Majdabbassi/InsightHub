package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsAnalysisResponse;
import com.dataanalytics.backend.dto.AnalysisResponse;
import com.dataanalytics.backend.event.RelationshipScanRequestedEvent;
import com.dataanalytics.backend.exception.AnalysisNotFoundException;
import com.dataanalytics.backend.model.AnalysisResult;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisResultRepository analysisResultRepository;
    private final DatasetService datasetService;
    private final ProjectService projectService;
    private final FileStorageService fileStorageService;
    private final AnalyticsClient analyticsClient;
    private final ObjectMapper objectMapper;
    private final ProjectContextCache projectContextCache;
    private final InsightsService insightsService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public AnalysisResponse analyze(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);

        var storedFile = fileStorageService.resolveExisting(dataset.getStoredFilePath());
        AnalyticsAnalysisResponse result =
                analyticsClient.analyze(storedFile, dataset.getOriginalFilename());

        AnalysisResult entity = analysisResultRepository.findByDatasetId(dataset.getId())
                .orElseGet(() -> AnalysisResult.builder().dataset(dataset).build());

        entity.setRowCount((int) result.rowCount());
        entity.setColumnCount(result.columnCount());
        entity.setDuplicateRowCount((int) result.duplicateRowCount());
        entity.setTotalMissingValues(result.summary().totalMissingValues());
        entity.setColumnStatsJson(toJson(result.columns()));
        entity.setCorrelationsJson(
                result.correlations() == null ? "[]" : toJson(result.correlations()));
        entity.setDataQualityJson(
                result.dataQuality() == null ? null : toJson(result.dataQuality()));

        // Phase 3 left these nullable — fill them in now that we know the real shape.
        dataset.setRowCount((int) result.rowCount());
        dataset.setColumnCount(result.columnCount());

        AnalysisResult saved = analysisResultRepository.save(entity);
        projectContextCache.invalidate(projectId);
        insightsService.invalidateDatasetSnapshots(dataset.getId());

        // Follow-up relationship detection; runs async AFTER this transaction
        // commits so it never blocks or breaks the analysis response.
        applicationEventPublisher.publishEvent(
                new RelationshipScanRequestedEvent(projectId, datasetId));

        return toResponse(saved, dataset.getId());
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
