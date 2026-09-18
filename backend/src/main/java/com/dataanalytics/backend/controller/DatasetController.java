package com.dataanalytics.backend.controller;

import com.dataanalytics.backend.dto.AnalysisResponse;
import com.dataanalytics.backend.dto.AnalyticsCleaningSuggestion;
import com.dataanalytics.backend.dto.CleanedDatasetResponse;
import com.dataanalytics.backend.dto.CsvPreviewResponse;
import com.dataanalytics.backend.dto.DatasetResponse;
import com.dataanalytics.backend.dto.SelectedCleaningAction;
import com.dataanalytics.backend.service.AnalysisService;
import com.dataanalytics.backend.service.CleaningService;
import com.dataanalytics.backend.service.DatasetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;
    private final AnalysisService analysisService;
    private final CleaningService cleaningService;

    @PostMapping
    public ResponseEntity<DatasetResponse> upload(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @RequestParam("file") MultipartFile file) {
        DatasetResponse response = datasetService.uploadDataset(userDetails.getUsername(), projectId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<DatasetResponse>> list(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PageableDefault(size = 50, sort = "uploadedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(
                datasetService.listDatasets(userDetails.getUsername(), projectId, pageable));
    }

    @GetMapping("/{datasetId}")
    public ResponseEntity<DatasetResponse> get(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId) {
        return ResponseEntity.ok(datasetService.getDataset(userDetails.getUsername(), projectId, datasetId));
    }

    @GetMapping("/{datasetId}/preview")
    public ResponseEntity<CsvPreviewResponse> preview(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId,
            @RequestParam(defaultValue = "50") int rows) {
        CsvPreviewResponse preview =
                datasetService.previewDataset(userDetails.getUsername(), projectId, datasetId, rows);
        return ResponseEntity.ok(preview);
    }

    @GetMapping("/{datasetId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId) {
        DatasetService.DownloadableFile file =
                datasetService.loadFileForDownload(userDetails.getUsername(), projectId, datasetId);

        // Build the header through Spring's ContentDisposition (RFC 5987
        // encoding) instead of string concatenation, so a crafted upload
        // filename cannot inject header content.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(safeDownloadName(file.filename()), StandardCharsets.UTF_8)
                                .build().toString())
                .body(file.resource());
    }

    /** Strips path separators and control characters from a download name. */
    private static String safeDownloadName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "dataset.csv";
        }
        String cleaned = filename
                .replaceAll("[\\p{Cntrl}]", "_")
                .replaceAll("[\\\\/]", "_")
                .replace("\"", "_")
                .trim();
        return cleaned.isEmpty() ? "dataset.csv" : cleaned;
    }

    @DeleteMapping("/{datasetId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId) {
        datasetService.deleteDataset(userDetails.getUsername(), projectId, datasetId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{datasetId}/analyze")
    public ResponseEntity<AnalysisResponse> analyze(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId) {
        AnalysisResponse response =
                analysisService.analyze(userDetails.getUsername(), projectId, datasetId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{datasetId}/analysis")
    public ResponseEntity<AnalysisResponse> getAnalysis(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId) {
        return ResponseEntity.ok(
                analysisService.getAnalysis(userDetails.getUsername(), projectId, datasetId));
    }

    @GetMapping("/{datasetId}/clean/suggestions")
    public ResponseEntity<List<AnalyticsCleaningSuggestion>> getCleaningSuggestions(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId) {
        List<AnalyticsCleaningSuggestion> suggestions = cleaningService.getSuggestions(
                userDetails.getUsername(), projectId, datasetId);
        return ResponseEntity.ok(suggestions);
    }

    @PostMapping("/{datasetId}/clean/apply")
    public ResponseEntity<CleanedDatasetResponse> applyCleaning(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PathVariable Long datasetId,
            @RequestBody @Valid List<SelectedCleaningAction> actions) {
        CleanedDatasetResponse response = cleaningService.apply(
                userDetails.getUsername(), projectId, datasetId, actions);
        return ResponseEntity.ok(response);
    }
}
