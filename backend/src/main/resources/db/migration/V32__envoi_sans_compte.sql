-- Envoi à un proche qui n'a pas encore de compte.
-- L'argent quitte le portefeuille de l'expéditeur pour un compte d'attente du grand livre. Il est versé
-- au proche quand il ouvre son compte avec ce numéro, ou rendu à l'expéditeur (frais compris) s'il annule
-- ou si personne ne l'a réclamé à temps.

INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000501', 'PENDING_TRANSFERS_USD', 'PENDING_TRANSFERS', 'USD', 'CREDIT', FALSE);

CREATE TABLE pending_transfer (
    id               UUID PRIMARY KEY,
    sender_id        UUID        NOT NULL REFERENCES customer (id),
    -- Clé envoyée par l'app : un double appui ne crée pas deux envois.
    idempotency_key  TEXT        NOT NULL,
    phone            TEXT        NOT NULL,
    -- Nom donné par l'expéditeur, pour lui seul (« Maman Chantal »).
    recipient_name   TEXT        NOT NULL CHECK (length(recipient_name) BETWEEN 1 AND 40),
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    fee_minor        BIGINT      NOT NULL CHECK (fee_minor >= 0),
    note             TEXT,
    status           TEXT        NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'CANCELLED', 'EXPIRED')),
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    settled_at       TIMESTAMPTZ,
    -- Client qui a reçu l'argent en ouvrant son compte.
    claimed_by       UUID REFERENCES customer (id),
    UNIQUE (sender_id, idempotency_key)
);

CREATE INDEX pending_transfer_phone_idx ON pending_transfer (phone) WHERE status = 'PENDING';
CREATE INDEX pending_transfer_expiry_idx ON pending_transfer (expires_at) WHERE status = 'PENDING';
CREATE INDEX pending_transfer_sender_idx ON pending_transfer (sender_id, created_at DESC);

-- Le proche n'est pas encore client : son SMS part vers un numéro, sans client ni marchand.
ALTER TABLE notification ADD COLUMN phone TEXT;
ALTER TABLE notification DROP CONSTRAINT notification_one_recipient;
ALTER TABLE notification ADD CONSTRAINT notification_one_recipient CHECK (num_nonnulls(customer_id, merchant_id, phone) = 1);
