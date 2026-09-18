package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsDataQuality;
import com.dataanalytics.backend.dto.ProjectOverviewResponse;
import com.dataanalytics.backend.dto.ProjectOverviewResponse.ProjectDatasetSummary;
import com.dataanalytics.backend.dto.ProjectOverviewResponse.QualityGradeBreakdown;
import com.dataanalytics.backend.dto.ProjectOverviewResponse.Stats;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import com.dataanalytics.backend.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverviewService {

    /**
     * A dataset counts as INACTIVE once anything in the project derives from
     * it (its id appears as another dataset's sourceDatasetId). For a chain
     * original -> clean1 -> clean2 this marks original and clean1 inactive,
     * leaving only the newest cleaned version active - which also covers
     * double-cleaning, because every earlier link gains a descendant. Two
     * independent cleaning branches of one original both count as active:
     * they are alternative current states, not superseded versions.
     */
    private final DatasetRepository datasetRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ProjectOverviewResponse getOverview(Project project) {
        List<Dataset> datasets = datasetRepository.findByProjectId(project.getId());

        Set<Long> supersededIds = new HashSet<>();
        for (Dataset dataset : datasets) {
            if (dataset.getSourceDatasetId() != null) {
                supersededIds.add(dataset.getSourceDatasetId());
            }
        }

        Map<Long, AnalyticsDataQuality> qualityByDataset = new HashMap<>();
        for (Dataset dataset : datasets) {
            analysisResultRepository.findByDatasetId(dataset.getId()).ifPresent(result ->
                    qualityByDataset.put(dataset.getId(), parseQuality(result.getDataQualityJson())));
        }

        List<ProjectDatasetSummary> items = new ArrayList<>();
        for (Dataset dataset : datasets) {
            AnalyticsDataQuality quality = qualityByDataset.get(dataset.getId());
            items.add(new ProjectDatasetSummary(
                    dataset.getId(),
                    dataset.getName(),
                    dataset.getRowCount(),
                    dataset.getColumnCount(),
                    quality == null ? null : quality.overallScore(),
                    quality == null ? null : quality.grade(),
                    dataset.getUploadedAt(),
                    !supersededIds.contains(dataset.getId()),
                    dataset.getSourceDatasetId(),
                    dataset.isCleanedVersion()));
        }
        // Active first; most recently uploaded on top inside each group.
        items.sort(Comparator
                .comparing((ProjectDatasetSummary item) -> item.isActive()).reversed()
                .thenComparing(Comparator.comparing(ProjectDatasetSummary::uploadedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))));

        List<ProjectDatasetSummary> activeItems =
                items.stream().filter(ProjectDatasetSummary::isActive).toList();
        return new ProjectOverviewResponse(
                buildStats(activeItems),
                buildStats(items),
                items);
    }

    private Stats buildStats(List<ProjectDatasetSummary> items) {
        long totalRows = 0;
        long totalColumns = 0;
        int scoreSum = 0;
        int scoredCount = 0;
        int lowQuality = 0;
        Map<String, Integer> grades = new HashMap<>();
        for (String grade : List.of("A", "B", "C", "D", "F")) {
            grades.put(grade, 0);
        }
        for (ProjectDatasetSummary item : items) {
            totalRows += item.rowCount() == null ? 0 : item.rowCount();
            totalColumns += item.columnCount() == null ? 0 : item.columnCount();
            if (item.qualityScore() != null) {
                scoreSum += item.qualityScore();
                scoredCount++;
            }
            if (item.qualityGrade() != null) {
                String grade = item.qualityGrade().toUpperCase();
                grades.merge(grade, 1, Integer::sum);
                if ("D".equals(grade) || "F".equals(grade)) {
                    lowQuality++;
                }
            }
        }
        double averageScore = scoredCount == 0 ? 0.0 : Math.round(scoreSum * 10.0 / scoredCount) / 10.0;
        return new Stats(
                items.size(),
                totalRows,
                totalColumns,
                averageScore,
                new QualityGradeBreakdown(
                        grades.get("A"), grades.get("B"), grades.get("C"),
                        grades.get("D"), grades.get("F")),
                lowQuality);
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
