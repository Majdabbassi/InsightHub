package com.dataanalytics.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AnalyticsServiceUnavailableException extends RuntimeException {

    public AnalyticsServiceUnavailableException(String message) {
        super(message);
    }
}
