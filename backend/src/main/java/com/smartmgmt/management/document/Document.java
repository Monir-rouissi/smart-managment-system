package com.smartmgmt.management.document;

import java.time.Instant;

import com.smartmgmt.common.BaseEntity;
import com.smartmgmt.management.project.Project;
import com.smartmgmt.management.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "documents")
public class Document extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Path on disk, relative to the configured documents storage root. Never exposed via the API. */
    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.UPLOADED;

    /** Which embedding model produced this document's chunks. Null until ingestion succeeds. */
    @Column(name = "embedding_model", length = 60)
    private String embeddingModel;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** Why the last ingestion attempt failed. Cleared when a later attempt succeeds. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "processed_at")
    private Instant processedAt;
}
