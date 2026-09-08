package com.smartmgmt.management.document;

/** Wraps I/O failures while writing or reading a document on disk. */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentStorageException(String message) {
        super(message);
    }
}
