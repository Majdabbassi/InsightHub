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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private TransactionTemplate readOnlyTransactionTemplate;

    @PostConstruct
    void createTransactionTemplates() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    public AnalyticsTrendInsightsResponse getTrends(
            String ownerEmail, Long projectId, Long datasetId, boolean forceRefresh) {
        DatasetContext ctx = datasetContextWithAnalysis(ownerEmail, projectId, datasetId);
        return cached(ctx, DatasetInsightSnapshot.InsightType.TREND, forceRefresh,
                AnalyticsTrendInsightsResponse.class,
                () -> analyticsClient.trends(fileOf(ctx.dataset()), ctx.filename()));
    }

    public AnalyticsPerformersResponse getPerformers(
            String ownerEmail, Long projectId, Long datasetId, boolean forceRefresh) {
        DatasetContext ctx = datasetContextWithAnalysis(ownerEmail, projectId, datasetId);
        return cached(ctx, DatasetInsightSnapshot.InsightType.TOP_BOTTOM_PERFORMERS,
                forceRefresh, AnalyticsPerformersResponse.class,
                () -> analyticsClient.performers(fileOf(ctx.dataset()), ctx.filename()));
    }

    public AnalyticsAnomaliesResponse getAnomalies(
            String ownerEmail, Long projectId, Long datasetId, boolean forceRefresh) {
        DatasetContext ctx = datasetContextWithAnalysis(ownerEmail, projectId, datasetId);
        return cached(ctx, DatasetInsightSnapshot.InsightType.ANOMALY, forceRefresh,
                AnalyticsAnomaliesResponse.class,
                () -> analyticsClient.anomalies(fileOf(ctx.dataset()), ctx.filename()));
    }

    /**
     * Period comparison results depend on the requested period parameters.
     * Only fully default calls (no periodType, no custom dates) are cached —
     * any parameterised call always computes fresh so users never see one
     * range's numbers served for another's.
     */
    public AnalyticsPeriodComparisonResponse getPeriodComparison(
            String ownerEmail, Long projectId, Long datasetId,
            String periodType, String customCurrentStart, String customCurrentEnd,
            String customPreviousStart, String customPreviousEnd,
            boolean forceRefresh) {
        DatasetContext ctx = datasetContextWithAnalysis(ownerEmail, projectId, datasetId);

        validatePeriodParams(periodType,
                customCurrentStart, customCurrentEnd, customPreviousStart, customPreviousEnd);

        boolean cacheable = !isPresent(periodType)
                && !isPresent(customCurrentStart) && !isPresent(customCurrentEnd)
                && !isPresent(customPreviousStart) && !isPresent(customPreviousEnd);
        if (!cacheable) {
            return analyticsClient.periodComparison(
                    fileOf(ctx.dataset()), ctx.filename(),
                    periodType, customCurrentStart, customCurrentEnd,
                    customPreviousStart, customPreviousEnd);
        }
        return cached(ctx, DatasetInsightSnapshot.InsightType.PERIOD_COMPARISON,
                forceRefresh, AnalyticsPeriodComparisonResponse.class,
                () -> analyticsClient.periodComparison(
                        fileOf(ctx.dataset()), ctx.filename(),
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

    /** Simple fields of the owned, analysed dataset needed beyond one tx. */
    private record DatasetContext(Dataset dataset) {
        Long id() {
            return dataset.getId();
        }

        String filename() {
            return dataset.getOriginalFilename();
        }
    }

    /**
     * Loads the dataset after ownership + analysis checks (shared preamble).
     * All lazy associations are resolved inside one read-only transaction, so
     * the returned context can be used safely once the transaction is over.
     */
    private DatasetContext datasetContextWithAnalysis(
            String ownerEmail, Long projectId, Long datasetId) {
        return readOnlyTransactionTemplate.execute(status -> {
            projectService.findOwnedProject(ownerEmail, projectId);
            Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);
            // Semantic roles come from the stored analysis; without them we cannot
            // compute or interpret insights for this dataset.
            analysisResultRepository.findByDatasetId(dataset.getId())
                    .orElseThrow(() -> new DashboardRequiresAnalysisException(datasetId));
            return new DatasetContext(dataset);
        });
    }

    /**
     * Cache-aside: return the stored snapshot unless it is absent or a refresh
     * was forced; otherwise call FastAPI once and upsert the snapshot.
     * Freshness is structural — invalidation deletes rows on data changes, so
     * any surviving row was computed from the current data.
     */
    private <T> T cached(DatasetContext ctx, DatasetInsightSnapshot.InsightType type,
            boolean forceRefresh, Class<T> responseType, Supplier<T> computer) {
        if (!forceRefresh) {
            var existing = snapshotRepository.findByDataset_IdAndInsightType(ctx.id(), type);
            if (existing.isPresent()) {
                try {
                    return objectMapper.readValue(existing.get().getResultJson(), responseType);
                } catch (Exception e) {
                    log.warn("Stored {} snapshot for dataset {} unreadable, recomputing",
                            type, ctx.id(), e);
                }
            }
        }
        // The computation is a (potentially slow) analytics HTTP round-trip —
        // it runs OUTSIDE any transaction so no DB connection is held while it
        // is in flight. Only the snapshot upsert below opens a short one.
        T result = computer.get();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                DatasetInsightSnapshot snapshot = snapshotRepository
                        .findByDataset_IdAndInsightType(ctx.id(), type)
                        .orElseGet(() -> DatasetInsightSnapshot.builder()
                                .dataset(ctx.dataset())
                                .insightType(type)
                                .build());
                snapshot.setResultJson(objectMapper.writeValueAsString(result));
                snapshot.setComputedAt(LocalDateTime.now());
                snapshotRepository.save(snapshot);
            });
        } catch (Exception e) {
            log.warn("Could not persist {} snapshot for dataset {}", type, ctx.id(), e);
        }
        return result;
    }

    private static final java.util.Set<String> VALID_PERIOD_TYPES =
            java.util.Set.of("day", "week", "month", "quarter");

    /** Everything the sibling-comparison HTTP call needs, captured in one tx. */
    private record SiblingComparisonInputs(
            Path fileA, String filenameA, String labelA,
            Path fileB, String filenameB, String labelB,
            String analysisJsonA, String analysisJsonB) {
    }

    /**
     * Sibling comparison between two datasets of the project. Deliberately
     * does NOT require a detected SIBLING relationship — the endpoint is
     * usable for ad-hoc comparisons of any two analysed datasets; the UI only
     * surfaces it for confirmed siblings.
     */
    public AnalyticsSiblingComparisonResponse getSiblingComparison(
            String ownerEmail, Long projectId, Long datasetAId, Long datasetBId) {
        // Lazy associations and file resolution happen inside one read-only
        // transaction; the analytics HTTP call itself runs outside it.
        SiblingComparisonInputs inputs = readOnlyTransactionTemplate.execute(status -> {
            projectService.findOwnedProject(ownerEmail, projectId);
            Dataset datasetA = datasetService.findDatasetInProject(projectId, datasetAId);
            Dataset datasetB = datasetService.findDatasetInProject(projectId, datasetBId);
            if (datasetA.getId().equals(datasetB.getId())) {
                throw new InvalidFileException("Pick two different datasets to compare.");
            }

            AnalysisResult analysisA = requireAnalysis(datasetA);
            AnalysisResult analysisB = requireAnalysis(datasetB);

            return new SiblingComparisonInputs(
                    fileOf(datasetA), datasetA.getOriginalFilename(), labelOf(datasetA),
                    fileOf(datasetB), datasetB.getOriginalFilename(), labelOf(datasetB),
                    partialAnalysisJson(analysisA), partialAnalysisJson(analysisB));
        });
        return analyticsClient.siblingComparison(
                inputs.fileA(), inputs.filenameA(), inputs.labelA(),
                inputs.fileB(), inputs.filenameB(), inputs.labelB(),
                inputs.analysisJsonA(), inputs.analysisJsonB());
    }

    /** Everything the relational-insights HTTP call needs, captured in one tx. */
    private record RelationalInsightInputs(
            Path parentFile, String parentFilename,
            Path childFile, String childFilename,
            String parentColumn, String childColumn,
            String matchPercentage, String analysisParentJson, String analysisChildJson) {
    }

    /**
     * Cross-dataset findings (referential completeness + join aggregate) for
     * one CONFIRMED FOREIGN_KEY relationship. SUGGESTED relationships must be
     * confirmed first; SIBLING links use the sibling-comparison endpoint.
     */
    public AnalyticsRelationalInsightResponse getRelationalInsights(
            String ownerEmail, Long projectId, Long relationshipId) {
        // The relationship's and datasets' lazy associations are resolved
        // inside one read-only transaction; the analytics HTTP call runs
        // outside it.
        RelationalInsightInputs inputs = readOnlyTransactionTemplate.execute(status -> {
            DatasetRelationship relationship =
                    relationshipService.findOwnedRelationship(ownerEmail, projectId, relationshipId);
            if (relationship.getRelationshipType()
                    != DatasetRelationship.RelationshipType.FOREIGN_KEY) {
                throw new InvalidRelationshipException(
                        "Relational insights are only available for foreign-key relationships. "
                                + "This link is a sibling relationship — use the sibling comparison instead.");
            }
            if (relationship.getStatus() != DatasetRelationship.RelationshipStatus.CONFIRMED) {
                throw new InvalidRelationshipException(
                        "Confirm this relationship first — relational insights are only "
                                + "computed for confirmed foreign-key links.");
            }

            // Detected FK rows store child -> parent: datasetA repeats values
            // that datasetB holds uniquely.
            Dataset parent = relationship.getDatasetB();
            Dataset child = relationship.getDatasetA();
            AnalysisResult analysisParent = requireAnalysis(parent);
            AnalysisResult analysisChild = requireAnalysis(child);

            String matchPercentage = relationship.getMatchPercentage() == null
                    ? null : String.valueOf(relationship.getMatchPercentage());
            return new RelationalInsightInputs(
                    fileOf(parent), parent.getOriginalFilename(),
                    fileOf(child), child.getOriginalFilename(),
                    relationship.getSharedColumnB(), relationship.getSharedColumnA(),
                    matchPercentage,
                    partialAnalysisJson(analysisParent), partialAnalysisJson(analysisChild));
        });
        return analyticsClient.relationalInsights(
                inputs.parentFile(), inputs.parentFilename(),
                inputs.childFile(), inputs.childFilename(),
                inputs.parentColumn(), inputs.childColumn(),
                inputs.matchPercentage(),
                inputs.analysisParentJson(), inputs.analysisChildJson());
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
