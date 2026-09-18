package com.dataanalytics.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class DatasetNotFoundException extends RuntimeException {

    public DatasetNotFoundException(Long datasetId) {
        super("Dataset not found with id: " + datasetId);
    }
}
