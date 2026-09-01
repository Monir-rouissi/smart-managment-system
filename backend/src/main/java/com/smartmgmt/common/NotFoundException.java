package com.smartmgmt.common;

import java.util.UUID;

/** Thrown when a lookup by id finds nothing; mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String entity, UUID id) {
        super("%s %s not found".formatted(entity, id));
    }

    public NotFoundException(String message) {
        super(message);
    }
}
