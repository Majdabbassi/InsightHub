package com.dataanalytics.backend.exception;

/** Raised when a cleaning operation fails (bad action combo, analytics errors). */
public class CleaningFailedException extends RuntimeException {

    public CleaningFailedException(String message) {
        super(message);
    }

    public CleaningFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
