package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsAnomaliesResponse;
import com.dataanalytics.backend.dto.AnalyticsColumnCorrelation;
import com.dataanalytics.backend.dto.AnalyticsColumnStats;
import com.dataanalytics.backend.dto.AnalyticsDataQuality;
import com.dataanalytics.backend.dto.AnalyticsPeriodComparisonResponse;
import com.dataanalytics.backend.dto.AnalyticsPerformersResponse;
import com.dataanalytics.backend.dto.AnalyticsTrendInsightsResponse;
import com.dataanalytics.backend.dto.ProjectOverviewResponse;
import com.dataanalytics.backend.dto.SelectedCleaningAction;
import com.dataanalytics.backend.model.CleaningJob;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.DatasetInsightSnapshot;
import com.dataanalytics.backend.model.DatasetRelationship;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import com.dataanalytics.backend.repository.CleaningJobRepository;
import com.dataanalytics.backend.repository.DatasetInsightSnapshotRepository;
import com.dataanalytics.backend.repository.DatasetRelationshipRepository;
import com.dataanalytics.backend.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a compact, human-readable summary of everything the app already
 * knows about a project (stats, per-dataset quality, cleaning lineage,
 * confirmed relationships, persisted insight snapshots) so the AI assistant
 * can answer grounded questions.
 *
 * Only PRE-COMPUTED information goes in here: insight one-liners come from
 * DatasetInsightSnapshot rows (populated when the user opens an insights
 * view) and are skipped for datasets where none exist yet - computing fresh
 * insights during a chat request would be far too slow, so they are never
 * triggered from here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectContextBuilder {

    /** Full detail for at most this many active datasets; rest get one line. */
    private static final int DATASET_DETAIL_LIMIT = 4;

    /** Cap on "notable column" lines per dataset to keep prompts small. */
    private static final int MAX_NOTABLE_COLUMNS = 4;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DatasetRepository datasetRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final DatasetRelationshipRepository relationshipRepository;
    private final CleaningJobRepository cleaningJobRepository;
    private final DatasetInsightSnapshotRepository insightSnapshotRepository;
    private final OverviewService overviewService;
    private final ChartSuggestionService chartSuggestionService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String buildContext(Project project) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: \"").append(project.getName()).append("\"\n\n");

        ProjectOverviewResponse overview = overviewService.getOverview(project);
        appendProjectStats(sb, overview);
        List<Dataset> active = activeDatasets(project.getId());
        appendDatasets(sb, active);
        appendRelationships(sb, project.getId());
        appendSqlCatalog(sb, active);
        // The bundle contains the project's full statistics and dataset
        // metadata, so it is only logged when debug logging is enabled.
        log.debug("[AI context] bundle for project {}:{}", project.getId(), sb);
        return sb.toString();
    }

    /** Active = not superseded by a cleaned version; newest uploads first. */
    private List<Dataset> activeDatasets(Long projectId) {
        List<Dataset> datasets = datasetRepository.findByProjectId(projectId);
        Set<Long> supersededIds = new HashSet<>();
        for (Dataset dataset : datasets) {
            if (dataset.getSourceDatasetId() != null) {
                supersededIds.add(dataset.getSourceDatasetId());
            }
        }
        return datasets.stream()
                .filter(dataset -> !supersededIds.contains(dataset.getId()))
                .sorted(Comparator.comparing(
                        Dataset::getUploadedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private void appendProjectStats(StringBuilder sb, ProjectOverviewResponse overview) {
        ProjectOverviewResponse.Stats stats = overview.activeStats();
        sb.append("Project statistics (current/active datasets only):\n")
                .append("- Active datasets: ").append(stats.datasetCount()).append("\n");
        if (stats.datasetCount() > 0) {
            sb.append("- Total rows across active datasets: ").append(stats.totalRows()).append("\n")
                    .append("- Total columns across active datasets: ")
                    .append(stats.totalColumns()).append("\n");
            if (scoredCount(overview) > 0) {
                sb.append("- Average data quality score: ")
                        .append(stats.averageQualityScore()).append("/100\n");
                ProjectOverviewResponse.QualityGradeBreakdown grades =
                        stats.qualityGradeBreakdown();
                sb.append("- Quality grades: A=").append(grades.A())
                        .append(", B=").append(grades.B())
                        .append(", C=").append(grades.C())
                        .append(", D=").append(grades.D())
                        .append(", F=").append(grades.F()).append("\n");
                if (stats.lowQualityDatasetCount() > 0) {
                    sb.append("- Warning: ").append(stats.lowQualityDatasetCount())
                            .append(" active dataset(s) have a D or F quality grade.\n");
                }
            } else {
                sb.append("- No dataset has been analyzed yet.\n");
            }
        }
        sb.append("\n");
    }

    private long scoredCount(ProjectOverviewResponse overview) {
        return overview.datasets().stream()
                .filter(d -> d.isActive() && d.qualityScore() != null)
                .count();
    }

    private void appendDatasets(StringBuilder sb, List<Dataset> active) {
        if (active.isEmpty()) {
            sb.append("## Datasets\nThis project has no active datasets.\n\n");
            return;
        }
        sb.append("## Active datasets\n");
        List<Dataset> detailed = active.subList(0, Math.min(active.size(), DATASET_DETAIL_LIMIT));
        for (Dataset dataset : detailed) {
            appendDatasetDetail(sb, active, dataset);
        }
        if (active.size() > detailed.size()) {
            sb.append("\nOther active datasets (summary):\n");
            for (Dataset dataset : active.subList(detailed.size(), active.size())) {
                sb.append("- ").append(dataset.getName())
                        .append(" (").append(dataset.getRowCount()).append(" rows)");
                analysisResultRepository.findByDatasetId(dataset.getId()).ifPresent(result -> {
                    AnalyticsDataQuality quality = parseQuality(result.getDataQualityJson());
                    if (quality != null) {
                        sb.append(", quality score ").append(quality.overallScore())
                                .append("/100 (grade ").append(quality.grade()).append(")");
                    }
                });
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    private void appendDatasetDetail(StringBuilder sb, List<Dataset> all, Dataset dataset) {
        sb.append("### ").append(dataset.getName()).append("\n");
        sb.append("- ").append(dataset.getRowCount()).append(" rows x ")
                .append(dataset.getColumnCount()).append(" columns, uploaded ")
                .append(dataset.getUploadedAt() == null
                        ? "unknown date"
                        : DATE_FMT.format(dataset.getUploadedAt()))
                .append("\n");

        if (dataset.getSourceDatasetId() != null) {
            String sourceName = all.stream()
                    .filter(d -> d.getId().equals(dataset.getSourceDatasetId()))
                    .map(Dataset::getName)
                    .findFirst().orElse("dataset #" + dataset.getSourceDatasetId());
            sb.append("- This is a cleaned version of \"").append(sourceName).append("\".\n");
        }

        appendCleaningHistory(sb, dataset);

        var analysisOpt = analysisResultRepository.findByDatasetId(dataset.getId());
        if (analysisOpt.isEmpty()) {
            sb.append("- Not analyzed yet (no data-quality score available).\n\n");
            return;
        }
        AnalyticsDataQuality quality = parseQuality(analysisOpt.get().getDataQualityJson());
        if (quality == null) {
            sb.append("- Not analyzed yet (no data-quality score available).\n\n");
            return;
        }
        sb.append("- Data quality score: ").append(quality.overallScore())
                .append("/100 (grade ").append(quality.grade())
                .append("), mainly limited by ")
                .append(weakestComponentName(quality)).append(".\n");

        List<AnalyticsColumnStats> columns = chartSuggestionService.parseColumns(
                analysisOpt.get().getColumnStatsJson());
        appendNotableColumns(sb, columns);
        appendCorrelations(sb, chartSuggestionService.parseCorrelations(
                analysisOpt.get().getCorrelationsJson()));
        appendInsightSummaries(sb, dataset);
        sb.append("\n");
    }

    /**
     * One line per insight type that already has a persisted snapshot for
     * this dataset (trend / anomaly / performers / period comparison). Types
     * the user has never opened produce nothing — never computed on demand.
     */
    private void appendInsightSummaries(StringBuilder sb, Dataset dataset) {
        appendInsight(sb, dataset, DatasetInsightSnapshot.InsightType.TREND,
                AnalyticsTrendInsightsResponse.class, result ->
                        result.trends() == null || result.trends().isEmpty()
                                ? null : result.trends().get(0).summary());
        appendInsight(sb, dataset, DatasetInsightSnapshot.InsightType.ANOMALY,
                AnalyticsAnomaliesResponse.class, result -> {
                    if (result.anomalies() == null || result.anomalies().isEmpty()) {
                        return null;
                    }
                    String summary = result.anomalies().get(0).summary();
                    int extra = result.anomalies().size() - 1;
                    return extra > 0
                            ? summary + " (+" + extra + " more anomalous period(s))"
                            : summary;
                });
        appendInsight(sb, dataset, DatasetInsightSnapshot.InsightType.TOP_BOTTOM_PERFORMERS,
                AnalyticsPerformersResponse.class, result ->
                        result.performers() == null || result.performers().isEmpty()
                                ? null : result.performers().get(0).summary());
        appendInsight(sb, dataset, DatasetInsightSnapshot.InsightType.PERIOD_COMPARISON,
                AnalyticsPeriodComparisonResponse.class, result ->
                        result.comparisons() == null || result.comparisons().isEmpty()
                                ? null : result.comparisons().get(0).summary());
    }

    private <T> void appendInsight(StringBuilder sb, Dataset dataset,
            DatasetInsightSnapshot.InsightType type, Class<T> responseType,
            java.util.function.Function<T, String> summaryOf) {
        var snapshot = insightSnapshotRepository
                .findByDataset_IdAndInsightType(dataset.getId(), type);
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            T parsed = objectMapper.readValue(snapshot.get().getResultJson(), responseType);
            String summary = summaryOf.apply(parsed);
            if (summary != null && !summary.isBlank()) {
                sb.append("- ").append(insightLabel(type)).append(": ").append(summary).append("\n");
            }
        } catch (Exception e) {
            log.warn("Could not render {} one-liner for dataset {}",
                    type, dataset.getId(), e);
        }
    }

    private static String insightLabel(DatasetInsightSnapshot.InsightType type) {
        return switch (type) {
            case TREND -> "Trend";
            case ANOMALY -> "Anomaly";
            case TOP_BOTTOM_PERFORMERS -> "Performers";
            case PERIOD_COMPARISON -> "Period comparison";
        };
    }

    /**
     * One line per column that has something worth knowing. Columns with no
     * findings (fully populated, valid, no outliers) are left out to keep the
     * bundle small; their absence means "nothing notable".
     */
    private void appendNotableColumns(StringBuilder sb, List<AnalyticsColumnStats> columns) {
        List<String> notes = new ArrayList<>();
        for (AnalyticsColumnStats column : columns) {
            List<String> issues = new ArrayList<>();
            if (column.missingPercentage() >= 5.0) {
                issues.add(column.missingPercentage() + "% missing values");
            }
            if (column.invalidValueCount() > 0) {
                issues.add(column.invalidValueCount() + " invalid value(s)");
            }
            if (column.outlierAnalysis() != null && column.outlierAnalysis().outlierCount() > 0) {
                issues.add(column.outlierAnalysis().outlierCount() + " outlier(s)");
            }
            if (!issues.isEmpty()) {
                notes.add("  - " + column.name() + " (" + column.dataType()
                        + "): " + String.join(", ", issues));
            }
        }
        if (notes.isEmpty()) {
            sb.append("- No notable column-level issues detected.\n");
        } else {
            sb.append("- Notable columns:\n");
            notes.stream().limit(MAX_NOTABLE_COLUMNS)
                    .forEach(note -> sb.append(note).append("\n"));
        }
    }

    private void appendCorrelations(StringBuilder sb, List<AnalyticsColumnCorrelation> correlations) {
        if (correlations.isEmpty()) {
            return;
        }
        sb.append("- Strongest correlations:\n");
        correlations.stream()
                .sorted(Comparator.comparingDouble((AnalyticsColumnCorrelation c) ->
                        Math.abs(c.correlation())).reversed())
                .limit(2)
                .forEach(c -> sb.append("  - ").append(c.columnA()).append(" vs ")
                        .append(c.columnB()).append(": ").append(c.strength())
                        .append(" ").append(c.direction()).append(" (r=")
                        .append(Math.round(c.correlation() * 100) / 100.0).append(")\n"));
    }

    private void appendCleaningHistory(StringBuilder sb, Dataset dataset) {
        List<CleaningJob> jobs = cleaningJobRepository
                .findBySourceDataset_ProjectIdOrderByCreatedAtAsc(dataset.getProject().getId())
                .stream()
                .filter(job -> job.getResultingDataset() != null
                        && dataset.getId().equals(job.getResultingDataset().getId()))
                .toList();
        for (CleaningJob job : jobs) {
            if (job.getStatus() == CleaningJob.Status.COMPLETED) {
                sb.append("- Produced by cleaning on ").append(
                        job.getCreatedAt() == null ? "unknown date" : DATE_FMT.format(job.getCreatedAt()));
                sb.append(": actions [").append(describeActions(job))
                        .append("], rows ").append(job.getRowsBefore()).append(" -> ")
                        .append(job.getRowsAfter());
                if (job.getValuesFilled() != null && job.getValuesFilled() > 0) {
                    sb.append(", ").append(job.getValuesFilled()).append(" value(s) filled");
                }
                sb.append("\n");
            }
        }
    }

    private String describeActions(CleaningJob job) {
        try {
            List<SelectedCleaningAction> actions = objectMapper.readValue(
                    job.getAppliedActionsJson(),
                    new TypeReference<List<SelectedCleaningAction>>() {});
            return actions.stream()
                    .map(action -> action.actionType().name().toLowerCase().replace('_', ' ')
                            + (action.columnName() == null ? "" : " (" + action.columnName() + ")"))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("unknown");
        } catch (Exception e) {
            log.warn("Could not describe cleaning actions for job {}", job.getId(), e);
            return "unknown";
        }
    }

    private void appendRelationships(StringBuilder sb, Long projectId) {
        List<DatasetRelationship> confirmed = relationshipRepository
                .findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(rel -> rel.getStatus() == DatasetRelationship.RelationshipStatus.CONFIRMED)
                .toList();
        if (confirmed.isEmpty()) {
            sb.append("## Confirmed relationships between datasets\n")
                    .append("None confirmed yet.\n");
            return;
        }
        sb.append("## Confirmed relationships between datasets\n");
        for (DatasetRelationship rel : confirmed) {
            if (rel.getRelationshipType() == DatasetRelationship.RelationshipType.SIBLING) {
                sb.append("- \"").append(rel.getDatasetA().getName()).append("\" and \"")
                        .append(rel.getDatasetB().getName()).append("\" appear to be siblings")
                        .append(rel.getMatchPercentage() == null ? "" :
                                " (~" + rel.getMatchPercentage() + "% schema overlap)")
                        .append("\n");
            } else {
                sb.append("- \"").append(rel.getDatasetA().getName()).append("\".")
                        .append(rel.getSharedColumnA()).append(" references \"")
                        .append(rel.getDatasetB().getName()).append("\".")
                        .append(rel.getSharedColumnB())
                        .append(rel.getMatchPercentage() == null ? "" :
                                " (~" + rel.getMatchPercentage() + "% of values matched)")
                        .append("\n");
            }
        }
    }

    /**
     * Table catalog for the assistant's optional live-query action: the
     * sanitized DuckDB table name of every analyzed active dataset with its
     * columns and semantic roles. Unanalyzed datasets are omitted — their
     * column structure is unknown, so queries against them would be guesses.
     */
    private void appendSqlCatalog(StringBuilder sb, List<Dataset> active) {
        // Match the executor's naming exactly: same dataset set, ordered by
        // id ascending (ChatService.resolveTables uses the same order), so the
        // names advertised here are the names the sandbox will bind.
        List<Dataset> byIdAscending = active.stream()
                .sorted(Comparator.comparing(Dataset::getId))
                .toList();
        Map<Long, String> namesById = SqlTableNames.uniqueTableNames(byIdAscending);

        boolean any = false;
        for (Dataset dataset : active) {
            var analysisOpt = analysisResultRepository.findByDatasetId(dataset.getId());
            if (analysisOpt.isEmpty()) {
                continue;
            }
            List<AnalyticsColumnStats> columns = chartSuggestionService.parseColumns(
                    analysisOpt.get().getColumnStatsJson());
            if (columns.isEmpty()) {
                continue;
            }
            if (!any) {
                sb.append("## SQL query catalog\n")
                        .append("Sanitized table names usable in read-only SQL queries:\n");
                any = true;
            }
            sb.append("- ").append(namesById.get(dataset.getId()))
                    .append(" (\"").append(dataset.getOriginalFilename()).append("\", ")
                    .append(dataset.getRowCount()).append(" rows): ");
            List<String> parts = new ArrayList<>();
            for (AnalyticsColumnStats column : columns) {
                parts.add(column.name() + " (" + column.dataType() + "/"
                        + column.semanticRole() + ")");
            }
            sb.append(String.join(", ", parts)).append("\n");
        }
        if (any) {
            sb.append("\n");
        }
    }

    private String weakestComponentName(AnalyticsDataQuality quality) {
        if (quality.breakdown() == null) {
            return "an unspecified factor";
        }
        var breakdown = quality.breakdown();
        double min = Double.MAX_VALUE;
        String name = "an unspecified factor";
        for (var component : new Object[][]{
                {"completeness (missing values)", breakdown.completeness()},
                {"uniqueness (duplicate rows)", breakdown.uniqueness()},
                {"consistency", breakdown.consistency()},
                {"validity", breakdown.validity()}}) {
            if ((double) component[1] < min) {
                min = (double) component[1];
                name = (String) component[0];
            }
        }
        return name;
    }

    private AnalyticsDataQuality parseQuality(String dataQualityJson) {
        if (dataQualityJson == null || dataQualityJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(dataQualityJson, AnalyticsDataQuality.class);
        } catch (Exception e) {
            log.warn("Could not parse stored data quality JSON", e);
            return null;
        }
    }
}
