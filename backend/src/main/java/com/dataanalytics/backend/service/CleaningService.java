package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsCleaningSuggestion;
import com.dataanalytics.backend.dto.CleanedDatasetResponse;
import com.dataanalytics.backend.dto.DatasetResponse;
import com.dataanalytics.backend.dto.SelectedCleaningAction;
import com.dataanalytics.backend.model.CleaningJob;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.repository.CleaningJobRepository;
import com.dataanalytics.backend.repository.DatasetRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates data cleaning: advisory suggestions plus apply-flows that
 * always produce a NEW dataset — the original is never modified.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CleaningService {

    private final CleaningJobRepository cleaningJobRepository;
    private final DatasetRepository datasetRepository;
    private final ProjectService projectService;
    private final DatasetService datasetService;
    private final FileStorageService fileStorageService;
    private final AnalyticsClient analyticsClient;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;
    private final ProjectContextCache projectContextCache;
    private final InsightsService insightsService;

    @Transactional(readOnly = true)
    public List<AnalyticsCleaningSuggestion> getSuggestions(
            String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);

        Path storedFile = fileStorageService.resolveExisting(dataset.getStoredFilePath());
        return analyticsClient.cleaningSuggestions(storedFile, dataset.getOriginalFilename());
    }

    /**
     * Not transactional on purpose: the analytics HTTP round-trip can be slow,
     * and failures must persist a FAILED job record (which a single rolling-back
     * transaction would undo). Each repository save commits independently.
     */
    public CleanedDatasetResponse apply(
            String ownerEmail,
            Long projectId,
            Long datasetId,
            List<SelectedCleaningAction> actions) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset source = datasetService.findDatasetInProject(projectId, datasetId);
        Path storedFile = fileStorageService.resolveExisting(source.getStoredFilePath());

        CleaningJob job = cleaningJobRepository.save(CleaningJob.builder()
                .sourceDataset(source)
                .appliedActionsJson(toJson(actions))
                .status(CleaningJob.Status.PENDING)
                .build());

        try {
            AnalyticsClient.CleaningApplyResult result =
                    analyticsClient.applyCleaning(storedFile, source.getOriginalFilename(), actions);

            Dataset cleaned = createCleanedDataset(projectId, source, result.csvBytes());
            cleaned = datasetRepository.save(cleaned);

            // Analyze immediately so the cleaned dataset opens with full stats.
            analysisService.analyze(ownerEmail, projectId, cleaned.getId());

            job.setResultingDataset(cleaned);
            job.setStatus(CleaningJob.Status.COMPLETED);
            job.setRowsBefore(result.summary().rowsBefore());
            job.setRowsAfter(result.summary().rowsAfter());
            job.setValuesFilled(result.summary().valuesFilled());
            job = cleaningJobRepository.save(job);

            projectContextCache.invalidate(projectId);
            // The source file itself is untouched, but cleaning changes the
            // project's data landscape — drop the source's cached insights so
            // everything the AI context sees is recomputed post-clean.
            insightsService.invalidateDatasetSnapshots(source.getId());
            return new CleanedDatasetResponse(
                    job.getId(),
                    source.getId(),
                    DatasetResponse.from(cleaned),
                    result.summary().rowsBefore(),
                    result.summary().rowsAfter(),
                    result.summary().rowsRemoved(),
                    result.summary().valuesFilled());
        } catch (RuntimeException e) {
            job.setStatus(CleaningJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            cleaningJobRepository.save(job);
            throw e;
        }
    }

    private Dataset createCleanedDataset(
            Long projectId, Dataset source, byte[] cleanedCsvBytes) {
        Project project = source.getProject();

        String baseName = source.getName();
        String name = baseName.endsWith(" (cleaned)")
                ? baseName + " " + System.currentTimeMillis()
                : baseName + " (cleaned)";

        String storedPath = fileStorageService.storeBytes(
                projectId, source.getOriginalFilename(), cleanedCsvBytes);

        return Dataset.builder()
                .name(name)
                .originalFilename(cleanedFilename(source.getOriginalFilename()))
                .storedFilePath(storedPath)
                .fileSizeBytes((long) cleanedCsvBytes.length)
                .project(project)
                .sourceDatasetId(source.getId())
                .isCleanedVersion(true)
                .build();
    }

    private String cleanedFilename(String originalFilename) {
        int dot = originalFilename.lastIndexOf('.');
        String stem = dot > 0 ? originalFilename.substring(0, dot) : originalFilename;
        return stem + "_cleaned.csv";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize cleaning actions", e);
        }
    }
}
