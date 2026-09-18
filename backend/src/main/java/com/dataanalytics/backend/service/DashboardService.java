package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsColumnStats;
import com.dataanalytics.backend.dto.ChartDataResponse;
import com.dataanalytics.backend.dto.ChartSuggestion;
import com.dataanalytics.backend.dto.DashboardResponse;
import com.dataanalytics.backend.dto.KpiResponse;
import com.dataanalytics.backend.exception.DashboardRequiresAnalysisException;
import com.dataanalytics.backend.model.AnalysisResult;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final AnalysisResultRepository analysisResultRepository;
    private final DatasetService datasetService;
    private final ProjectService projectService;
    private final FileStorageService fileStorageService;
    private final ChartSuggestionService chartSuggestionService;
    private final ChartDataService chartDataService;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);

        AnalysisResult result = analysisResultRepository.findByDatasetId(dataset.getId())
                .orElseThrow(() -> new DashboardRequiresAnalysisException(datasetId));

        long totalRows = result.getRowCount();
        int totalCols = result.getColumnCount();
        long totalMissing = result.getTotalMissingValues();
        long dupes = result.getDuplicateRowCount();

        long totalCells = (long) totalRows * totalCols;
        double missingPct = totalCells > 0
                ? Math.round((double) totalMissing / totalCells * 10000.0) / 100.0
                : 0.0;
        double dupePct = totalRows > 0
                ? Math.round((double) dupes / totalRows * 10000.0) / 100.0
                : 0.0;

        KpiResponse kpis = new KpiResponse(totalRows, totalCols, missingPct, dupePct);
        List<ChartSuggestion> charts = chartSuggestionService.generateSuggestions(
                result, fileStorageService.resolveExisting(dataset.getStoredFilePath()));

        return new DashboardResponse(kpis, charts);
    }

    @Transactional(readOnly = true)
    public ChartDataResponse getChartData(
            String ownerEmail, Long projectId, Long datasetId,
            String xColumn, String yColumn, String type, String aggregation) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = datasetService.findDatasetInProject(projectId, datasetId);

        AnalysisResult result = analysisResultRepository.findByDatasetId(dataset.getId())
                .orElseThrow(() -> new DashboardRequiresAnalysisException(datasetId));

        // The x column's semantic role drives chart behaviour (e.g.
        // NUMERIC_DISCRETE bars are ordered by value, not frequency).
        String xRole = chartSuggestionService.parseColumns(result.getColumnStatsJson()).stream()
                .filter(c -> xColumn.equals(c.name()))
                .findFirst()
                .map(AnalyticsColumnStats::semanticRole)
                .orElse(null);

        Path filePath = fileStorageService.resolveExisting(dataset.getStoredFilePath());
        return chartDataService.computeAggregation(
                filePath, xColumn, yColumn, type, aggregation, xRole);
    }
}
