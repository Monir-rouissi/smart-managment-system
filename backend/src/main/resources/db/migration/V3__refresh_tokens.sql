-- Refresh tokens for JWT auth. The raw token value is only ever returned to
-- the client once, at issue time; we persist its HMAC (see
-- JwtService#hashRefreshToken), never the raw value, so a DB leak alone
-- cannot be replayed. Refresh rotates the token: the presented row is
-- marked revoked and a new row is inserted.

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(200) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
