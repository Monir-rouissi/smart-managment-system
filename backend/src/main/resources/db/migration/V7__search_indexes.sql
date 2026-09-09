-- Phase 7: keyword (Postgres FTS) + semantic (pgvector ANN) search.

-- Full-text over chunk content.
--
-- The two-argument to_tsvector('english', ...) is required: the one-argument form
-- depends on the session's default_text_search_config and is therefore not
-- IMMUTABLE, so Postgres rejects it in a generated column. A generated column is
-- used rather than a trigger so the vector can never drift from the content, and
-- so existing rows are populated by this ALTER with no backfill script.
--
-- 'english' stems ("termination" matches "terminate"). It is the wrong analyzer
-- for a French or Arabic corpus, and one column cannot be per-document
-- multilingual -- see the README before changing it.
ALTER TABLE document_chunks
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX idx_document_chunks_tsv ON document_chunks USING GIN (content_tsv);

-- Full-text over the document name, kept on `documents` rather than denormalised
-- onto every chunk: a rename would otherwise have to rewrite every chunk row, and
-- a missed update would make search disagree with the document list.
--
-- 'simple' (no stemming) because file names are not prose: "Q3_contract_v2.pdf".
ALTER TABLE documents
    ADD COLUMN name_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', name)) STORED;

CREATE INDEX idx_documents_name_tsv ON documents USING GIN (name_tsv);

-- Approximate nearest-neighbour index for semantic search.
--
-- HNSW rather than IVFFlat: IVFFlat has to be built after the data is loaded (it
-- trains centroids) and its `lists` parameter has to be retuned as the corpus
-- grows. HNSW builds incrementally and needs no tuning; its higher build cost and
-- memory use are irrelevant at this size.
--
-- vector_cosine_ops MUST match the operator used by the query (<=>). Querying with
-- <-> (L2) would silently ignore this index -- and because the vectors are unit
-- normalised, L2 and cosine rank identically, so the mistake would be invisible in
-- the results and visible only in EXPLAIN.
--
-- Below roughly ten thousand rows the planner will often prefer a sequential scan,
-- which is exact and fast enough. That is a correct plan, not a missing index.
CREATE INDEX idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
