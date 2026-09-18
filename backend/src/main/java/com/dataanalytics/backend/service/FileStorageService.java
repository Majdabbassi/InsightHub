package com.dataanalytics.backend.service;

import com.dataanalytics.backend.exception.FileStorageException;
import com.dataanalytics.backend.exception.InvalidFileException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final String CSV_EXTENSION = ".csv";    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv",
            "application/csv",
            "application/vnd.ms-excel",
            "text/comma-separated-values",
            "application/octet-stream");

    private final Path storageRoot;

    public FileStorageService(@Value("${file.storage-path}") String storagePath) {
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        init();
    }

    private void init() {
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new FileStorageException("Could not create upload directory: " + storageRoot, e);
        }
    }

    /**
     * Validates the uploaded file and stores it under {storageRoot}/{projectId}/{uuid}_{filename}.
     * Returns the absolute path of the stored file.
     */
    public String store(Long projectId, MultipartFile file) {
        validate(projectId, file);

        String sanitized = sanitizeFilename(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + "_" + sanitized;

        Path projectDir = storageRoot.resolve(String.valueOf(projectId)).normalize();
        if (!projectDir.startsWith(storageRoot)) {
            throw new InvalidFileException("Invalid file name");
        }

        try {
            Files.createDirectories(projectDir);
            Path target = projectDir.resolve(storedName);
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (IOException e) {
            throw new FileStorageException("Failed to store file: " + sanitized, e);
        }
    }

    /**
     * Stores raw bytes (e.g. a cleaned CSV produced by the analytics service)
     * under {storageRoot}/{projectId}/{uuid}_{filename}. Returns the absolute path.
     */
    public String storeBytes(Long projectId, String filename, byte[] content) {
        if (projectId == null || projectId <= 0) {
            throw new InvalidFileException("Invalid project id");
        }
        if (content == null || content.length == 0) {
            throw new FileStorageException("Refusing to store an empty file");
        }

        String sanitized = sanitizeFilename(filename);
        String storedName = UUID.randomUUID() + "_" + sanitized;

        Path projectDir = storageRoot.resolve(String.valueOf(projectId)).normalize();
        if (!projectDir.startsWith(storageRoot)) {
            throw new InvalidFileException("Invalid file name");
        }

        try {
            Files.createDirectories(projectDir);
            Path target = projectDir.resolve(storedName);
            Files.write(target, content);
            return target.toString();
        } catch (IOException e) {
            throw new FileStorageException("Failed to store file: " + sanitized, e);
        }
    }

    /**
     * Deletes the file at the given path. Missing files are ignored so that
     * dataset cleanup never blocks the DB delete.
     */
    public void deleteFile(String storedFilePath) {
        try {
            Path path = Paths.get(storedFilePath).normalize();
            if (!path.startsWith(storageRoot)) {
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new FileStorageException("Failed to delete file: " + storedFilePath, e);
        }
    }

    /**
     * Resolves a stored file path, ensuring it lives inside the storage root.
     * Throws FileStorageException when the file does not exist.
     */
    public Path resolveExisting(String storedFilePath) {
        try {
            Path path = Paths.get(storedFilePath).normalize();
            if (!path.startsWith(storageRoot) || !Files.exists(path)) {
                throw new FileStorageException("Stored file is missing: " + path.getFileName());
            }
            return path;
        } catch (InvalidFileException | FileStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new FileStorageException("Invalid stored file path", e);
        }
    }

    private void validate(Long projectId, MultipartFile file) {
        if (projectId == null || projectId <= 0) {
            throw new InvalidFileException("Invalid project id");
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Uploaded file is empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null
                || !originalFilename.toLowerCase(Locale.ROOT).endsWith(CSV_EXTENSION)) {
            throw new InvalidFileException("Only .csv files are allowed");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new InvalidFileException("Invalid content type: only CSV files are accepted");
        }
    }

    private String sanitizeFilename(String originalFilename) {
        String name = Paths.get(originalFilename).getFileName().toString();
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
