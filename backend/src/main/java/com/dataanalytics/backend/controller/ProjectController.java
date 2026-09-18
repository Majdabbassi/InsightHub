package com.dataanalytics.backend.controller;

import com.dataanalytics.backend.dto.CreateProjectRequest;
import com.dataanalytics.backend.dto.ProjectOverviewResponse;
import com.dataanalytics.backend.dto.ProjectResponse;
import com.dataanalytics.backend.dto.UpdateProjectRequest;
import com.dataanalytics.backend.service.OverviewService;
import com.dataanalytics.backend.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final OverviewService overviewService;

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse response = projectService.createProject(userDetails.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(projectService.listProjects(userDetails.getUsername()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> get(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(projectService.getProject(userDetails.getUsername(), id));
    }

    @GetMapping("/{id}/overview")
    public ResponseEntity<ProjectOverviewResponse> overview(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(overviewService.getOverview(
                projectService.findOwnedProject(userDetails.getUsername(), id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id,
            @Valid @RequestBody UpdateProjectRequest request) {
        return ResponseEntity.ok(projectService.updateProject(userDetails.getUsername(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        projectService.deleteProject(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
