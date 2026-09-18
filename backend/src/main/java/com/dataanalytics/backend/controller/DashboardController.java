package com.dataanalytics.backend.controller;

import com.dataanalytics.backend.dto.ChartDataResponse;
import com.dataanalytics.backend.dto.DashboardResponse;
import com.dataanalytics.backend.service.DashboardService;
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
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> getDashboard(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId) {
        return ResponseEntity.ok(
                dashboardService.getDashboard(userDetails.getUsername(), projectId, datasetId));
    }

    @GetMapping("/chart-data")
    public ResponseEntity<ChartDataResponse> getChartData(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId,
            @RequestParam String xColumn,
            @RequestParam(defaultValue = "") String yColumn,
            @RequestParam(defaultValue = "BAR") String type,
            @RequestParam(defaultValue = "COUNT") String aggregation) {
        return ResponseEntity.ok(
                dashboardService.getChartData(
                        userDetails.getUsername(), projectId, datasetId,
                        xColumn, yColumn.isEmpty() ? null : yColumn,
                        type, aggregation));
    }
}
