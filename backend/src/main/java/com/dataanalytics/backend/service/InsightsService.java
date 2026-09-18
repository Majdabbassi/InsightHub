package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsAnomaliesResponse;
import com.dataanalytics.backend.dto.AnalyticsPeriodComparisonResponse;
import com.dataanalytics.backend.dto.AnalyticsPerformersResponse;
import com.dataanalytics.backend.dto.AnalyticsRelationalInsightResponse;
import com.dataanalytics.backend.dto.AnalyticsSiblingComparisonResponse;
import com.dataanalytics.backend.dto.AnalyticsTrendInsightsResponse;
import com.dataanalytics.backend.exception.DashboardRequiresAnalysisException;
import com.dataanalytics.backend.exception.InvalidFileException;
import com.dataanalytics.backend.exception.InvalidRelationshipException;
import com.dataanalytics.backend.model.AnalysisResult;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.DatasetInsightSnapshot;
import com.dataanalytics.backend.model.DatasetRelationship;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import com.dataanalytics.backend.repository.DatasetInsightSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Insights features. The four per-dataset insight types (trends, period
 * comparison, anomalies, top/bottom performers) are computed by the analytics
 * service and PERSISTED as one DatasetInsightSnapshot per type, so repeat
 * requests are served from the database and the AI assistant's context
 * builder can read them without triggering a live computation. Snapshots are
 * invalidated (deleted) when the dataset is re-analysed or cleaned; a
 * forceRefresh request bypasses and replaces them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InsightsService {

    private final AnalysisResultRepository analysisResultRepository;
    private final DatasetInsightSnapshotRepository snapshotRepository;
    private final DatasetService datasetService;
    private final ProjectService projectService;
    private final FileStorageService fileStorageService;
    private final AnalyticsClient analyticsClient;
    private final RelationshipService relationshipService;
    private final ObjectMapper objectMapper;

    @Transactional
    public AnalyticsTrendInsightsResponse getTrends(
            String ownerEmail, Long projectId, Long datasetId, boolean forceRefresh) {
        Dataset dataset = datasetWithAnalysis(ownerEmail, projectId, datasetId);
        return cached(dataset, DatasetInsightSnapshot.InsightType.TREND, forceRefresh,
                AnalyticsTrendInsightsResponse.class,
                () -> analyticsClient.trends(fileOf(dataset), dataset.getOriginalFilename()));
    }

    @Transactional
    public AnalyticsPerformersResponse getPerformers(
            String ownerEmail, Long projectId, Long datasetId, boolean forceRefresh) {
        Dataset dataset = datasetWithAnalysis(ownerEmail, projectId, datasetId);
        return cached(dataset, DatasetInsightSnapshot.InsightType.TOP_BOTTOM_PERFORMERS,
                forceRefresh, AnalyticsPerformersResponse.class,
                () -> analyticsClient.performers(fileOf(dataset), dataset.getOriginalFilename()));
    }

    @Transactional
    public AnalyticsAnomaliesResponse getAnomalies(
            String ownerEmail, Long projectId, Long datasetId, boolean forceRefresh) {
        Dataset dataset = datasetWithAnalysis(ownerEmail, projectId, datasetId);
        return cached(dataset, DatasetInsightSnapshot.InsightType.ANOMALY, forceRefresh,
                AnalyticsAnomaliesResponse.class,
                () -> analyticsClient.anomalies(fileOf(dataset), dataset.getOriginalFilename()));
    }

    /**
     * Period comparison results depend on the requested period parameters.
     * Only fully default calls (no periodType, no custom dates) are cached —
     * any parameterised call always computes fresh so users never see one
     * range's numbers served for another's.
     */
    @Transactional
    public AnalyticsPeriodComparisonResponse getPeriodComparison(
            String ownerEmail, Long projectId, Long datasetId,
            String periodType, String customCurrentStart, String customCurrentEnd,
            String customPreviousStart, String customPreviousEnd,
            boolean forceRefresh) {
        Dataset dataset = datasetWithAnalysis(ownerEmail, projectId, datasetId);

        validatePeriodParams(periodType,
                customCurrentStart, customCurrentEnd, customPreviousStart, customPreviousEnd);

        boolean cacheable = !isPresent(periodType)
                && !isPresent(customCurrentStart) && !isPresent(customCurrentEnd)
                && !isPresent(customPreviousStart) && !isPresent(customPreviousEnd);
        if (!cacheable) {
            return analyticsClient.periodComparison(
                    fileOf(dataset), dataset.getOriginalFilename(),
                    periodType, customCurrentStart, customCurrentEnd,
                    customPreviousStart, customPreviousEnd);
        }
        return cached(dataset, DatasetInsightSnapshot.InsightType.PERIOD_COMPARISON,
                forceRefresh, AnalyticsPeriodComparisonResponse.class,
                () -> analyticsClient.periodComparison(
                        fileOf(dataset), dataset.getOriginalFilename(),
                        null, null, null, null, null));
    }

    /**
     * Deletes every stored snapshot of one dataset. Called when its data
     * changes (re-analysis or cleaning); the next insight request recomputes
     * from scratch, so stale rows can never be served.
     */
    @Transactional
    public void invalidateDatasetSnapshots(Long datasetId) {
        snapshotRepository.deleteByDataset_Id(datasetId);
    }

    /** Loads the dataset after ownership + analysis checks (shared preamble). */
    private Dataset datasetWithAnalysis(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);
        // Semantic roles come from the stored analysis; without them we cannot
        // compute or interpret insights for this dataset.
        analysisResultRepository.findByDatasetId(dataset.getId())
                .orElseThrow(() -> new DashboardRequiresAnalysisException(datasetId));
        return dataset;
    }

    /**
     * Cache-aside: return the stored snapshot unless it is absent or a refresh
     * was forced; otherwise call FastAPI once and upsert the snapshot.
     * Freshness is structural — invalidation deletes rows on data changes, so
     * any surviving row was computed from the current data.
     */
    private <T> T cached(Dataset dataset, DatasetInsightSnapshot.InsightType type,
            boolean forceRefresh, Class<T> responseType, Supplier<T> computer) {
        if (!forceRefresh) {
            var existing =
                    snapshotRepository.findByDataset_IdAndInsightType(dataset.getId(), type);
            if (existing.isPresent()) {
                try {
                    return objectMapper.readValue(existing.get().getResultJson(), responseType);
                } catch (Exception e) {
                    log.warn("Stored {} snapshot for dataset {} unreadable, recomputing",
                            type, dataset.getId(), e);
                }
            }
        }
        T result = computer.get();
        try {
            DatasetInsightSnapshot snapshot = snapshotRepository
                    .findByDataset_IdAndInsightType(dataset.getId(), type)
                    .orElseGet(() -> DatasetInsightSnapshot.builder()
                            .dataset(dataset)
                            .insightType(type)
                            .build());
            snapshot.setResultJson(objectMapper.writeValueAsString(result));
            snapshot.setComputedAt(LocalDateTime.now());
            snapshotRepository.save(snapshot);
        } catch (Exception e) {
            log.warn("Could not persist {} snapshot for dataset {}", type, dataset.getId(), e);
        }
        return result;
    }

    private static final java.util.Set<String> VALID_PERIOD_TYPES =
            java.util.Set.of("day", "week", "month", "quarter");

    /**
     * Sibling comparison between two datasets of the project. Deliberately
     * does NOT require a detected SIBLING relationship — the endpoint is
     * usable for ad-hoc comparisons of any two analysed datasets; the UI only
     * surfaces it for confirmed siblings.
     */
    @Transactional(readOnly = true)
    public AnalyticsSiblingComparisonResponse getSiblingComparison(
            String ownerEmail, Long projectId, Long datasetAId, Long datasetBId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset datasetA = datasetService.findDatasetInProject(projectId, datasetAId);
        Dataset datasetB = datasetService.findDatasetInProject(projectId, datasetBId);
        if (datasetA.getId().equals(datasetB.getId())) {
            throw new InvalidFileException("Pick two different datasets to compare.");
        }

        AnalysisResult analysisA = requireAnalysis(datasetA);
        AnalysisResult analysisB = requireAnalysis(datasetB);

        Path fileA = fileStorageService.resolveExisting(datasetA.getStoredFilePath());
        Path fileB = fileStorageService.resolveExisting(datasetB.getStoredFilePath());
        return analyticsClient.siblingComparison(
                fileA, datasetA.getOriginalFilename(), labelOf(datasetA),
                fileB, datasetB.getOriginalFilename(), labelOf(datasetB),
                partialAnalysisJson(analysisA), partialAnalysisJson(analysisB));
    }

    /**
     * Cross-dataset findings (referential completeness + join aggregate) for
     * one CONFIRMED FOREIGN_KEY relationship. SUGGESTED relationships must be
     * confirmed first; SIBLING links use the sibling-comparison endpoint.
     */
    @Transactional(readOnly = true)
    public AnalyticsRelationalInsightResponse getRelationalInsights(
            String ownerEmail, Long projectId, Long relationshipId) {
        DatasetRelationship relationship =
                relationshipService.findOwnedRelationship(ownerEmail, projectId, relationshipId);
        if (relationship.getRelationshipType() != DatasetRelationship.RelationshipType.FOREIGN_KEY) {
            throw new InvalidRelationshipException(
                    "Relational insights are only available for foreign-key relationships. "
                            + "This link is a sibling relationship — use the sibling comparison instead.");
        }
        if (relationship.getStatus() != DatasetRelationship.RelationshipStatus.CONFIRMED) {
            throw new InvalidRelationshipException(
                    "Confirm this relationship first — relational insights are only "
                            + "computed for confirmed foreign-key links.");
        }

        // Detected FK rows store child -> parent: datasetA repeats values that
        // datasetB holds uniquely.
        Dataset parent = relationship.getDatasetB();
        Dataset child = relationship.getDatasetA();
        AnalysisResult analysisParent = requireAnalysis(parent);
        AnalysisResult analysisChild = requireAnalysis(child);

        String matchPercentage = relationship.getMatchPercentage() == null
                ? null : String.valueOf(relationship.getMatchPercentage());
        return analyticsClient.relationalInsights(
                fileOf(parent), parent.getOriginalFilename(),
                fileOf(child), child.getOriginalFilename(),
                relationship.getSharedColumnB(), relationship.getSharedColumnA(),
                matchPercentage,
                partialAnalysisJson(analysisParent), partialAnalysisJson(analysisChild));
    }

    private AnalysisResult requireAnalysis(Dataset dataset) {
        return analysisResultRepository.findByDatasetId(dataset.getId())
                .orElseThrow(() -> new DashboardRequiresAnalysisException(dataset.getId()));
    }

    private Path fileOf(Dataset dataset) {
        return fileStorageService.resolveExisting(dataset.getStoredFilePath());
    }

    /** Stable human-readable name used in insight summaries. */
    private static String labelOf(Dataset dataset) {
        return dataset.getName();
    }

    /**
     * Wraps the stored per-column stats array into a partial analysis object
     * ({code {"columns": [...]}}) so FastAPI can reuse semantic roles without
     * re-deriving them. Returns null when nothing is stored, in which case
     * the analytics service recomputes.
     */
    private static String partialAnalysisJson(AnalysisResult analysis) {
        String columnStats = analysis.getColumnStatsJson();
        if (columnStats == null || !columnStats.strip().startsWith("[")) {
            return null;
        }
        return "{\"columns\":" + columnStats + "}";
    }

    private void validatePeriodParams(
            String periodType, String currentStart, String currentEnd,
            String previousStart, String previousEnd) {
        boolean anyCustom = isPresent(currentStart) || isPresent(currentEnd)
                || isPresent(previousStart) || isPresent(previousEnd);
        boolean allCustom = isPresent(currentStart) && isPresent(currentEnd)
                && isPresent(previousStart) && isPresent(previousEnd);

        if (anyCustom && !allCustom) {
            throw new InvalidFileException(
                    "All four custom dates (customCurrentStart, customCurrentEnd, "
                            + "customPreviousStart, customPreviousEnd) must be provided together.");
        }
        if (isPresent(periodType) && !VALID_PERIOD_TYPES.contains(periodType.toLowerCase())) {
            throw new InvalidFileException(
                    "Unsupported periodType '" + periodType
                            + "'. Use one of day, week, month, quarter.");
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
