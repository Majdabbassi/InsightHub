package com.dataanalytics.backend.exception;

/** No relationship with the given id visible in the given project (404). */
public class RelationshipNotFoundException extends RuntimeException {

    public RelationshipNotFoundException(Long relationshipId) {
        super("Relationship not found: " + relationshipId);
    }
}
