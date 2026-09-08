-- Phase 5-6: async ingestion. Chunks + embeddings for uploaded documents.
-- The `vector` extension is already enabled by V1.

ALTER TABLE documents
    ADD COLUMN embedding_model VARCHAR(60),
    ADD COLUMN chunk_count     INT         NOT NULL DEFAULT 0,
    ADD COLUMN error_message   TEXT,
    ADD COLUMN retry_count     INT         NOT NULL DEFAULT 0,
    ADD COLUMN processed_at    TIMESTAMPTZ;

-- Dimension is fixed at 1536 (text-embedding-3-small). Switching to a model with a
-- different dimension is a new column/table, not an ALTER -- see README.
CREATE TABLE document_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index     INT  NOT NULL,
    content         TEXT NOT NULL,
    page_or_section VARCHAR(120),
    token_count     INT  NOT NULL,
    embedding       vector(1536),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_chunk UNIQUE (document_id, chunk_index)
);

-- Lookup by document (timeline of a document's chunks). The ANN index for
-- similarity search belongs with the search query in Phase 7, not here: an
-- index on an empty table proves nothing and only slows ingestion inserts.
CREATE INDEX idx_document_chunks_document ON document_chunks (document_id, chunk_index);

-- Ingestion picks work up by status; the recovery scan filters on updated_at too.
CREATE INDEX idx_documents_status ON documents (status, updated_at);
