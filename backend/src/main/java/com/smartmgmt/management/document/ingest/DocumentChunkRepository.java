package com.smartmgmt.management.document.ingest;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    long countByDocumentId(UUID documentId);

    /**
     * Clears a document's chunks before a (re)ingest. Re-processing must replace,
     * never append -- otherwise a retry doubles the corpus.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentChunk c where c.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
