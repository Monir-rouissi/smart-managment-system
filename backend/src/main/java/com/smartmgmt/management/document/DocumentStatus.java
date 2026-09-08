package com.smartmgmt.management.document;

/**
 * Lifecycle of an uploaded document.
 *
 * <p>UPLOADED is also the queued state: the upload transaction commits the row
 * as UPLOADED and the ingestion worker claims it from there. Keeping one state
 * for "stored, not processed yet" avoids a second CHECK-constraint value that
 * would mean the same thing.
 */
public enum DocumentStatus {
    /** Stored on disk, waiting for (or queued for) ingestion. */
    UPLOADED,
    /** Claimed by an ingestion worker. */
    PROCESSING,
    /** Chunks and embeddings exist. */
    READY,
    /** The last ingestion attempt failed; see {@code errorMessage}. */
    FAILED
}
