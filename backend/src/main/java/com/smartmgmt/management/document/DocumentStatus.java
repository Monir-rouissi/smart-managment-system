package com.smartmgmt.management.document;

/**
 * Lifecycle of an uploaded document. Phase 4 only ever writes UPLOADED;
 * PROCESSING/READY/FAILED are reserved for the extract/chunk/embed phase.
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    READY,
    FAILED
}
