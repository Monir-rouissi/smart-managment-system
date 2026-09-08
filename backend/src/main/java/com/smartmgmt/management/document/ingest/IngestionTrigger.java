package com.smartmgmt.management.document.ingest;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Enqueues ingestion once the uploading transaction has committed.
 *
 * <p>Submitting from inside the transaction is the classic bug here: the worker
 * starts immediately, reads the document row on its own connection, and does not
 * find it -- intermittently, and more often on a fast machine.
 */
@Component
public class IngestionTrigger {

    private final IngestionQueue queue;

    public IngestionTrigger(IngestionQueue queue) {
        this.queue = queue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIngestRequested(DocumentIngestRequestedEvent event) {
        queue.submit(event.documentId());
    }
}
