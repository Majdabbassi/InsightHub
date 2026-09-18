package com.dataanalytics.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class AnalysisFailedException extends RuntimeException {

    public AnalysisFailedException(String message) {
        super(message);
    }
}
