-- Phase 4: document upload (no processing/embeddings yet).
-- Files are written to disk; this table only tracks their metadata.

CREATE TABLE documents (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,
    mime_type    VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    uploaded_by  UUID REFERENCES users (id)    ON DELETE SET NULL,
    project_id   UUID REFERENCES projects (id) ON DELETE CASCADE,
    status       VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED'
                 CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_documents_project_id  ON documents (project_id, created_at DESC);
CREATE INDEX idx_documents_uploaded_by ON documents (uploaded_by, created_at DESC);
