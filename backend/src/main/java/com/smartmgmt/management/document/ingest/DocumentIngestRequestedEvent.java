package com.smartmgmt.management.document.ingest;

import java.util.UUID;

/**
 * Published when a document should be (re)ingested. Handled after the
 * publishing transaction commits -- a worker that starts before the commit
 * would not find the row it was told about.
 */
public record DocumentIngestRequestedEvent(UUID documentId) {
}
