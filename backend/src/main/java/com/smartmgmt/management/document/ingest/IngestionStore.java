package com.smartmgmt.management.document.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.smartmgmt.management.document.Document;
import com.smartmgmt.management.document.DocumentRepository;
import com.smartmgmt.management.document.DocumentStatus;

/**
 * Every database write the ingestion pipeline makes, each in its own short
 * transaction.
 *
 * <p>It is a separate bean on purpose: {@link IngestionService} calls these
 * methods, and a {@code @Transactional} method called from inside the same bean
 * would bypass the proxy and silently run without a transaction. Keeping the
 * units here also keeps the (slow, network-bound) embedding call outside any
 * open transaction, so it cannot hold a connection from the pool.
 */
@Component
public class IngestionStore {

    private static final Logger log = LoggerFactory.getLogger(IngestionStore.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    /** A document may only be claimed from a settled state. */
    private static final Set<DocumentStatus> CLAIMABLE = Set.of(DocumentStatus.UPLOADED, DocumentStatus.FAILED);

    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public IngestionStore(DocumentRepository documents, DocumentChunkRepository chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    /**
     * Conditionally moves the row to PROCESSING and returns the work to do.
     *
     * <p>The condition is the whole concurrency control: a double submit, a retry
     * and the startup recovery scan can all race for the same document, and
     * exactly one UPDATE will match. An empty result means someone else won.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DocumentJob> claim(UUID documentId) {
        int claimed = documents.claimForProcessing(
                documentId, DocumentStatus.PROCESSING, CLAIMABLE, Instant.now());
        if (claimed == 0) {
            log.debug("Document {} was not claimable (already processing, or gone)", documentId);
            return Optional.empty();
        }
        return documents.findById(documentId)
                .map(d -> new DocumentJob(d.getId(), d.getName(), d.getMimeType(), d.getStoragePath()));
    }

    /** Replaces the document's chunks and marks it READY, in one transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(UUID documentId, String embeddingModel, List<Chunk> parts, List<float[]> vectors) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new IngestionException("Document vanished mid-ingest: " + documentId));

        // Replace, never append: a re-process must not double the corpus.
        chunks.deleteByDocumentId(documentId);

        List<DocumentChunk> entities = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Chunk part = parts.get(i);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocument(document);
            chunk.setChunkIndex(part.index());
            chunk.setContent(part.content());
            chunk.setPageOrSection(part.pageOrSection());
            chunk.setTokenCount(part.tokenCount());
            chunk.setEmbedding(vectors.get(i));
            entities.add(chunk);
        }
        chunks.saveAll(entities);

        document.setStatus(DocumentStatus.READY);
        document.setChunkCount(entities.size());
        document.setEmbeddingModel(embeddingModel);
        document.setProcessedAt(Instant.now());
        document.setErrorMessage(null);
        documents.save(document);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID documentId, String message) {
        documents.findById(documentId).ifPresent(document -> {
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage(truncate(message));
            document.setRetryCount(document.getRetryCount() + 1);
            documents.save(document);
        });
    }

    /**
     * Returns rows abandoned by a worker that died (or a JVM that restarted) to
     * the queued state, so they can be claimed again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseStale(Instant cutoff) {
        return documents.releaseStaleProcessing(DocumentStatus.UPLOADED, DocumentStatus.PROCESSING, cutoff);
    }

    @Transactional(readOnly = true)
    public List<UUID> findQueued(int limit) {
        return documents.findIdsByStatus(DocumentStatus.UPLOADED, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    private static String truncate(String message) {
        if (message == null) {
            return "Ingestion failed";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
