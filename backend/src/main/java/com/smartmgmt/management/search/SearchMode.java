package com.smartmgmt.management.search;

/** Which retrieval path a search request should use. */
public enum SearchMode {

    /** Postgres full-text search over chunk content and document name. */
    KEYWORD,

    /** Embedding similarity over document_chunks.embedding. */
    SEMANTIC,

    /** Both, merged with reciprocal rank fusion. */
    HYBRID
}
