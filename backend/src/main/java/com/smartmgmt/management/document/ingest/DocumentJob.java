package com.smartmgmt.management.document.ingest;

import java.util.UUID;

/**
 * The bits of a claimed document the pipeline needs, copied out of the entity so
 * no lazy proxy (and no {@code passwordHash}-bearing user graph) travels to the
 * worker thread.
 */
public record DocumentJob(UUID id, String name, String mimeType, String storagePath) {
}
