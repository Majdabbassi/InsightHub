package com.dataanalytics.backend.dto;

import com.dataanalytics.backend.model.Dataset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class DatasetResponse {

    private Long id;

    private String name;

    private String originalFilename;

    private Long fileSizeBytes;

    private Integer rowCount;

    private Integer columnCount;

    private LocalDateTime uploadedAt;

    private Long projectId;

    private Long sourceDatasetId;

    private boolean isCleanedVersion;

    public static DatasetResponse from(Dataset dataset) {
        return DatasetResponse.builder()
                .id(dataset.getId())
                .name(dataset.getName())
                .originalFilename(dataset.getOriginalFilename())
                .fileSizeBytes(dataset.getFileSizeBytes())
                .rowCount(dataset.getRowCount())
                .columnCount(dataset.getColumnCount())
                .uploadedAt(dataset.getUploadedAt())
                .projectId(dataset.getProject().getId())
                .sourceDatasetId(dataset.getSourceDatasetId())
                .isCleanedVersion(dataset.isCleanedVersion())
                .build();
    }
}
