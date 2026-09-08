package com.smartmgmt.management.document.ingest;

import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hands a document id to a worker thread.
 *
 * <p>The queue is in memory on purpose (no Kafka in this phase), which means a
 * restart loses whatever is still queued. That is survivable only because the
 * document row itself is the durable record of outstanding work:
 * {@link IngestionRecovery} re-submits anything left behind at startup.
 */
@Component
public class IngestionQueue {

    private static final Logger log = LoggerFactory.getLogger(IngestionQueue.class);

    private final Executor executor;
    private final ObjectProvider<IngestionService> service;
    private final IngestionProperties properties;

    public IngestionQueue(Executor ingestionExecutor, ObjectProvider<IngestionService> service,
            IngestionProperties properties) {
        this.executor = ingestionExecutor;
        this.service = service;
        this.properties = properties;
    }

    public void submit(UUID documentId) {
        if (!properties.isEnabled()) {
            log.debug("Ingestion disabled; leaving document {} unprocessed", documentId);
            return;
        }
        executor.execute(() -> {
            try {
                service.getObject().process(documentId);
            } catch (RuntimeException e) {
                // process() already records failures on the row; this is the last
                // line of defence so a worker thread is never killed by a task.
                log.error("Ingestion task for document {} terminated unexpectedly", documentId, e);
            }
        });
    }
}
