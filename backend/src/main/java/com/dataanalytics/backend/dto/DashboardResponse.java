package com.dataanalytics.backend.dto;

import java.util.List;

public record DashboardResponse(
        KpiResponse kpis,
        List<ChartSuggestion> suggestedCharts) {
}
