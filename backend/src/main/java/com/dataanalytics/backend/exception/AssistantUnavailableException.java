package com.dataanalytics.backend.exception;

/** Raised when Ollama is unreachable or fails; maps to HTTP 503. */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message) {
        super(message);
    }

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
