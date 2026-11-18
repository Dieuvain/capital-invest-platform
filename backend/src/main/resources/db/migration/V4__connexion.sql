-- Connexion : code SMS à usage unique, PIN et sessions.

ALTER TABLE customer
    ADD COLUMN pin_hash            TEXT,
    ADD COLUMN pin_failed_attempts SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until    TIMESTAMPTZ;

CREATE TABLE otp_challenge (
    id           UUID PRIMARY KEY,
    phone        TEXT        NOT NULL,
    purpose      TEXT        NOT NULL CHECK (purpose IN ('REGISTER', 'RESET_PIN')),
    code_hash    TEXT        NOT NULL,           -- HMAC-SHA256, jamais le code en clair
    attempts     SMALLINT    NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX otp_challenge_phone_idx ON otp_challenge (phone, created_at DESC);

CREATE TABLE session (
    token_hash    TEXT PRIMARY KEY,              -- SHA-256 du jeton, le jeton lui-même n'est jamais stocké
    customer_id   UUID        NOT NULL REFERENCES customer (id),
    device_id     TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ
);

CREATE INDEX session_customer_idx ON session (customer_id);
