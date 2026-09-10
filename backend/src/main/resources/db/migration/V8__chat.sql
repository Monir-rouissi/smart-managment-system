-- Phase 8: RAG chatbot. A conversation belongs to one user, optionally scoped to
-- one project (a project-level chat panel); a project-less conversation searches
-- everything that user can already see, via the same AccessScope rule search uses.

CREATE TABLE chat_conversation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id  UUID REFERENCES projects (id) ON DELETE CASCADE,
    title       VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_conversation_user ON chat_conversation (user_id, updated_at DESC);

-- Messages are an append-only log, not a mutable record -- no updated_at, same
-- call the (still unbuilt) audit_log design made. `sources` is null on a USER
-- message and, on failure, on an ASSISTANT message that answered "I don't know".
CREATE TABLE chat_message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES chat_conversation (id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content         TEXT NOT NULL,
    sources         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_conversation ON chat_message (conversation_id, created_at);
