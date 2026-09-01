-- Baseline migration.
-- Enables the pgvector extension so later phases (document embeddings) can add
-- vector columns. No domain tables yet — those arrive with the CRUD milestone.
CREATE EXTENSION IF NOT EXISTS vector;
