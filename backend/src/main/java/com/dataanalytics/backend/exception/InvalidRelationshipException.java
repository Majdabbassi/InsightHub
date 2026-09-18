package com.dataanalytics.backend.exception;

/** Semantically invalid relationship request (400) - e.g. datasets outside the project. */
public class InvalidRelationshipException extends RuntimeException {

    public InvalidRelationshipException(String message) {
        super(message);
    }
}
