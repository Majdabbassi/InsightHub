package com.dataanalytics.backend.controller;

import com.dataanalytics.backend.dto.AnalyticsAnomaliesResponse;
import com.dataanalytics.backend.dto.AnalyticsPeriodComparisonResponse;
import com.dataanalytics.backend.dto.AnalyticsPerformersResponse;
import com.dataanalytics.backend.dto.AnalyticsTrendInsightsResponse;
import com.dataanalytics.backend.service.InsightsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/datasets/{datasetId}")
@RequiredArgsConstructor
public class InsightsController {

    private final InsightsService insightsService;

    @GetMapping("/insights/trends")
    public ResponseEntity<AnalyticsTrendInsightsResponse> getTrends(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
        return ResponseEntity.ok(
                insightsService.getTrends(userDetails.getUsername(), projectId, datasetId, forceRefresh));
    }

    @GetMapping("/insights/top-bottom-performers")
    public ResponseEntity<AnalyticsPerformersResponse> getPerformers(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
        return ResponseEntity.ok(
                insightsService.getPerformers(userDetails.getUsername(), projectId, datasetId, forceRefresh));
    }

    @GetMapping("/insights/anomalies")
    public ResponseEntity<AnalyticsAnomaliesResponse> getAnomalies(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
        return ResponseEntity.ok(
                insightsService.getAnomalies(userDetails.getUsername(), projectId, datasetId, forceRefresh));
    }

    @GetMapping("/insights/period-comparison")
    public ResponseEntity<AnalyticsPeriodComparisonResponse> getPeriodComparison(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId,
            @RequestParam(required = false) String periodType,
            @RequestParam(required = false) String customCurrentStart,
            @RequestParam(required = false) String customCurrentEnd,
            @RequestParam(required = false) String customPreviousStart,
            @RequestParam(required = false) String customPreviousEnd,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
        return ResponseEntity.ok(insightsService.getPeriodComparison(
                userDetails.getUsername(), projectId, datasetId,
                periodType, customCurrentStart, customCurrentEnd,
                customPreviousStart, customPreviousEnd, forceRefresh));
    }
}
