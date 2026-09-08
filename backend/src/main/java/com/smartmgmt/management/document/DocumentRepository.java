package com.smartmgmt.management.document;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Override
    @EntityGraph(attributePaths = {"uploadedBy", "project"})
    Optional<Document> findById(UUID id);

    @EntityGraph(attributePaths = {"uploadedBy", "project"})
    List<Document> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /**
     * Claims a document for ingestion. The {@code status in :from} clause is the
     * whole concurrency control: when a double submit, a retry and the startup
     * recovery scan race for the same row, exactly one UPDATE matches.
     *
     * <p>{@code updatedAt} is set explicitly because a bulk update bypasses Spring
     * Data's auditing listener, and the recovery scan reads that column.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = :to, d.errorMessage = null, d.updatedAt = :now
             where d.id = :id and d.status in :from
            """)
    int claimForProcessing(@Param("id") UUID id,
            @Param("to") DocumentStatus to,
            @Param("from") Collection<DocumentStatus> from,
            @Param("now") Instant now);

    /** Returns rows stuck in PROCESSING (the worker or the JVM died) to the queued state. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = :to, d.updatedAt = :cutoff
             where d.status = :stuckIn and d.updatedAt < :cutoff
            """)
    int releaseStaleProcessing(@Param("to") DocumentStatus to,
            @Param("stuckIn") DocumentStatus stuckIn,
            @Param("cutoff") Instant cutoff);

    @Query("select d.id from Document d where d.status = :status order by d.createdAt asc")
    List<UUID> findIdsByStatus(@Param("status") DocumentStatus status, Pageable pageable);
}
