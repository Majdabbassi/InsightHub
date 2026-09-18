package com.dataanalytics.backend.controller;

import com.dataanalytics.backend.dto.AnalyticsRelationalInsightResponse;
import com.dataanalytics.backend.dto.AnalyticsSiblingComparisonResponse;
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

/**
 * Project-level insights spanning multiple datasets (as opposed to the
 * single-dataset insights under /datasets/{datasetId}/insights).
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class ProjectInsightsController {

    private final InsightsService insightsService;

    @GetMapping("/insights/sibling-comparison")
    public ResponseEntity<AnalyticsSiblingComparisonResponse> getSiblingComparison(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @RequestParam Long datasetAId,
            @RequestParam Long datasetBId) {
        return ResponseEntity.ok(insightsService.getSiblingComparison(
                userDetails.getUsername(), projectId, datasetAId, datasetBId));
    }

    @GetMapping("/relationships/{relationshipId}/insights")
    public ResponseEntity<AnalyticsRelationalInsightResponse> getRelationalInsights(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long relationshipId) {
        return ResponseEntity.ok(
                insightsService.getRelationalInsights(
                        userDetails.getUsername(), projectId, relationshipId));
    }
}
