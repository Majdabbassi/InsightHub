package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.CsvPreviewResponse;
import com.dataanalytics.backend.dto.DatasetResponse;
import com.dataanalytics.backend.exception.DatasetNotFoundException;
import com.dataanalytics.backend.exception.FileStorageException;
import com.dataanalytics.backend.exception.ForbiddenException;
import com.dataanalytics.backend.exception.InvalidFileException;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasetRepository;
    private final ProjectService projectService;
    private final FileStorageService fileStorageService;
    private final ProjectContextCache projectContextCache;

    @Transactional
    public DatasetResponse uploadDataset(String ownerEmail, Long projectId, MultipartFile file) {
        Project project = projectService.findOwnedProject(ownerEmail, projectId);

        String storedFilePath = fileStorageService.store(projectId, file);

        try {
            Dataset dataset = Dataset.builder()
                    .name(file.getOriginalFilename())
                    .originalFilename(file.getOriginalFilename())
                    .storedFilePath(storedFilePath)
                    .fileSizeBytes(file.getSize())
                    .project(project)
                    .build();

            DatasetResponse response = DatasetResponse.from(datasetRepository.save(dataset));
            projectContextCache.invalidate(projectId);
            return response;
        } catch (Exception e) {
            fileStorageService.deleteFile(storedFilePath);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<DatasetResponse> listDatasets(String ownerEmail, Long projectId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        return datasetRepository.findByProjectId(projectId).stream()
                .map(DatasetResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DatasetResponse getDataset(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        return DatasetResponse.from(findDatasetInProject(projectId, datasetId));
    }

    @Transactional
    public void deleteDataset(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = findDatasetInProject(projectId, datasetId);

        datasetRepository.delete(dataset);
        fileStorageService.deleteFile(dataset.getStoredFilePath());
        projectContextCache.invalidate(projectId);
    }

    public record DownloadableFile(Resource resource, String filename) {
    }

    @Transactional(readOnly = true)
    public DownloadableFile loadFileForDownload(String ownerEmail, Long projectId, Long datasetId) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = findDatasetInProject(projectId, datasetId);

        Path path = fileStorageService.resolveExisting(dataset.getStoredFilePath());
        return new DownloadableFile(new FileSystemResource(path), dataset.getOriginalFilename());
    }

    @Transactional(readOnly = true)
    public CsvPreviewResponse previewDataset(String ownerEmail, Long projectId, Long datasetId,
                                             int maxRows) {
        projectService.findOwnedProject(ownerEmail, projectId);
        Dataset dataset = findDatasetInProject(projectId, datasetId);

        Path path = fileStorageService.resolveExisting(dataset.getStoredFilePath());
        int limit = Math.min(Math.max(maxRows, 1), 500);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowDuplicateHeaderNames(true)
                .setIgnoreEmptyLines(true)
                .build();

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {

            List<String> columns = new ArrayList<>(parser.getHeaderNames());
            List<List<String>> rows = new ArrayList<>();

            for (CSVRecord record : parser) {
                if (rows.size() >= limit) {
                    break;
                }
                rows.add(record.toList());
            }
            return new CsvPreviewResponse(columns, rows);
        } catch (IOException e) {
            throw new InvalidFileException("Could not parse the CSV file: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new FileStorageException("Failed to read the stored file", e);
        }
    }

    /**
     * Loads a dataset after verifying the owning project chain. Shared with
     * AnalysisService for chained ownership checks.
     */
    public Dataset findDatasetInProject(Long projectId, Long datasetId) {
        Dataset dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException(datasetId));

        if (!dataset.getProject().getId().equals(projectId)) {
            throw new ForbiddenException("This dataset does not belong to the given project");
        }
        return dataset;
    }
}
