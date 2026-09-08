package com.smartmgmt.management.document.ingest;

/** A document could not be ingested. The message is stored on {@code documents.error_message}. */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
