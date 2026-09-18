package com.dataanalytics.backend.controller;

import com.dataanalytics.backend.dto.CreateRelationshipRequest;
import com.dataanalytics.backend.dto.RelationshipResponse;
import com.dataanalytics.backend.dto.ScanResponse;
import com.dataanalytics.backend.service.RelationshipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

@RestController
@RequestMapping("/api/projects/{projectId}/relationships")
@RequiredArgsConstructor
public class RelationshipController {

    private final RelationshipService relationshipService;

    @GetMapping
    public ResponseEntity<Page<RelationshipResponse>> list(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PageableDefault(size = 50)
            Pageable pageable) {
        return ResponseEntity.ok(
                relationshipService.list(userDetails.getUsername(), projectId, pageable));
    }

    /** Manually re-run detection across all dataset pairs of the project. */
    @PostMapping("/scan")
    public ResponseEntity<ScanResponse> scan(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId) {
        return ResponseEntity.ok(relationshipService.scan(userDetails.getUsername(), projectId));
    }

    /** Manually document a relationship (status MANUAL, matchPercentage null). */
    @PostMapping
    public ResponseEntity<RelationshipResponse> create(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @Valid @RequestBody CreateRelationshipRequest request) {
        RelationshipResponse response =
                relationshipService.createManual(userDetails.getUsername(), projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{relationshipId}/confirm")
    public ResponseEntity<RelationshipResponse> confirm(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long relationshipId) {
        return ResponseEntity.ok(
                relationshipService.confirm(userDetails.getUsername(), projectId, relationshipId));
    }

    @PutMapping("/{relationshipId}/reject")
    public ResponseEntity<RelationshipResponse> reject(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long relationshipId) {
        return ResponseEntity.ok(
                relationshipService.reject(userDetails.getUsername(), projectId, relationshipId));
    }

    @DeleteMapping("/{relationshipId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long relationshipId) {
        relationshipService.delete(userDetails.getUsername(), projectId, relationshipId);
        return ResponseEntity.noContent().build();
    }
}
