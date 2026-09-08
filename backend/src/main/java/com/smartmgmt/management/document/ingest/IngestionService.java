package com.smartmgmt.management.document.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smartmgmt.management.document.DocumentStorageService;

import jakarta.annotation.PostConstruct;

/**
 * The pipeline: claim -> extract -> chunk -> embed -> persist.
 *
 * <p>Deliberately not {@code @Transactional}. Each database write is a short
 * transaction inside {@link IngestionStore}; the slow parts (disk read, parsing,
 * embedding HTTP calls) run with no transaction open, so 200 chunks' worth of
 * network latency cannot pin a connection from the pool.
 *
 * <p>There is no {@code SecurityContext} on this thread -- it is a worker, not the
 * request thread, and the uploader's token may have expired by the time the job
 * runs. Nothing here may call a {@code @PreAuthorize} method; the uploader is
 * already recorded on the document row.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Must match {@code vector(1536)} in V6__document_chunks.sql. */
    public static final int EMBEDDING_DIMENSIONS = 1536;

    private final IngestionStore store;
    private final DocumentStorageService storage;
    private final TextExtractor extractor;
    private final Chunker chunker;
    private final EmbeddingClient embeddings;
    private final IngestionProperties properties;

    public IngestionService(IngestionStore store, DocumentStorageService storage, TextExtractor extractor,
            Chunker chunker, EmbeddingClient embeddings, IngestionProperties properties) {
        this.store = store;
        this.storage = storage;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    /**
     * The vector column's dimension is fixed by the migration, so a mismatched
     * configuration is a startup failure, not a per-row insert error hours later.
     */
    @PostConstruct
    void verifyEmbeddingDimensions() {
        if (properties.getDimensions() != EMBEDDING_DIMENSIONS || embeddings.dimensions() != EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException(
                    "Embedding dimension mismatch: document_chunks.embedding is vector(%d) but app.ingest.dimensions=%d and %s reports %d. Changing model dimension needs a new migration."
                            .formatted(EMBEDDING_DIMENSIONS, properties.getDimensions(),
                                    embeddings.modelId(), embeddings.dimensions()));
        }
    }

    public void process(UUID documentId) {
        Optional<DocumentJob> claimed = store.claim(documentId);
        if (claimed.isEmpty()) {
            return;
        }
        DocumentJob job = claimed.get();
        long startedAt = System.currentTimeMillis();
        try {
            byte[] bytes = read(job);
            ExtractedText text = extractor.extract(bytes, job.mimeType(), job.name());
            if (text.isBlank()) {
                throw new IngestionException("No extractable text found in " + job.name());
            }

            List<Chunk> parts = chunker.chunk(text);
            if (parts.isEmpty()) {
                throw new IngestionException("Document produced no chunks");
            }
            if (parts.size() > properties.getMaxChunksPerDocument()) {
                throw new IngestionException(
                        "Document would produce %d chunks, over the limit of %d. Split the file or raise app.ingest.max-chunks-per-document."
                                .formatted(parts.size(), properties.getMaxChunksPerDocument()));
            }

            List<float[]> vectors = embeddings.embed(parts.stream().map(Chunk::content).toList());
            verify(vectors, parts.size());

            store.markReady(documentId, embeddings.modelId(), parts, vectors);
            log.info("Ingested document {} ({}): {} chunks in {} ms",
                    documentId, job.name(), parts.size(), System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.warn("Ingestion failed for document {} ({})", documentId, job.name(), e);
            store.markFailed(documentId, describe(e));
        }
    }

    private byte[] read(DocumentJob job) {
        try (InputStream in = storage.load(job.storagePath()).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IngestionException("Stored file could not be read: " + job.storagePath(), e);
        }
    }

    private void verify(List<float[]> vectors, int expected) {
        if (vectors.size() != expected) {
            throw new IngestionException("Embedded %d chunks but expected %d".formatted(vectors.size(), expected));
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != EMBEDDING_DIMENSIONS) {
                throw new IngestionException("Embedding has %d dimensions, expected %d"
                        .formatted(vector == null ? 0 : vector.length, EMBEDDING_DIMENSIONS));
            }
        }
    }

    /** Keeps the stored error readable: message only, with the exception type when there is no message. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
