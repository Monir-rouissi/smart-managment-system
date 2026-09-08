package com.smartmgmt.management.document.ingest;

import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.smartmgmt.common.BaseEntity;
import com.smartmgmt.management.document.Document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One embedded slice of a document. Chunks are internal: they are never
 * returned by the API in this phase -- Phase 7 search is what exposes them,
 * and it will apply the same visibility rules as the parent document.
 */
@Getter
@Setter
@Entity
@Table(name = "document_chunks")
public class DocumentChunk extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** Page number ("p. 3") for PDFs, or a section hint; null when the format has none. */
    @Column(name = "page_or_section", length = 120)
    private String pageOrSection;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    /**
     * pgvector column. The dimension is fixed by the DDL, so a model whose
     * dimension differs is rejected at startup rather than failing per row.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1536)
    @Column(name = "embedding")
    private float[] embedding;
}
