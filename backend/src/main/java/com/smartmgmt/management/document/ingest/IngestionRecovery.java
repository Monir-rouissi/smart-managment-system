package com.smartmgmt.management.document.ingest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Picks up work the in-memory queue lost.
 *
 * <p>Without this, an ordinary restart leaves documents stuck in PROCESSING
 * forever and never processes what was still queued -- the real cost of not
 * having a durable broker. The document row is the queue; this scan is what
 * makes that true across restarts.
 */
@Component
public class IngestionRecovery {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecovery.class);
    private static final int MAX_RESUMED_PER_START = 500;

    private final IngestionStore store;
    private final IngestionQueue queue;
    private final IngestionProperties properties;

    public IngestionRecovery(IngestionStore store, IngestionQueue queue, IngestionProperties properties) {
        this.store = store;
        this.queue = queue;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterruptedWork() {
        if (!properties.isEnabled()) {
            return;
        }
        int released = store.releaseStale(Instant.now().minus(properties.getStaleProcessingAfter()));
        if (released > 0) {
            log.warn("Requeued {} document(s) left in PROCESSING by a previous run", released);
        }
        List<UUID> queued = store.findQueued(MAX_RESUMED_PER_START);
        if (!queued.isEmpty()) {
            log.info("Resuming ingestion for {} queued document(s)", queued.size());
            queued.forEach(queue::submit);
        }
    }
}
