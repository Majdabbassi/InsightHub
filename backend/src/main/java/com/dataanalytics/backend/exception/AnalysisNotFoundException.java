package com.dataanalytics.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class AnalysisNotFoundException extends RuntimeException {

    public AnalysisNotFoundException(Long datasetId) {
        super("Dataset " + datasetId + " has not been analyzed yet");
    }
}
