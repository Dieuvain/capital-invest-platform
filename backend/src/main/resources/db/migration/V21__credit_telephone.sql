-- Achat de crédit téléphone (unités) pour soi ou un proche, sur tous les réseaux.

-- Crédit prépayé chez le prestataire d'unités (actif) : chaque achat le diminue, un remboursement le rétablit.
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000401', 'AIRTIME_PROVIDER_USD', 'OPERATOR_SETTLEMENT', 'USD', 'DEBIT', TRUE);

CREATE TABLE airtime_purchase (
    id                     UUID PRIMARY KEY,
    customer_id            UUID        NOT NULL REFERENCES customer (id),
    idempotency_key        TEXT        NOT NULL,
    network                TEXT        NOT NULL CHECK (network IN ('VODACOM', 'AIRTEL', 'ORANGE', 'AFRICELL')),
    msisdn                 TEXT        NOT NULL,      -- 243…, sans « + »
    amount_minor           BIGINT      NOT NULL CHECK (amount_minor > 0),
    status                 TEXT        NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    provider               TEXT        NOT NULL,
    provider_reference     TEXT,
    failure_message        TEXT,
    hold_transaction_id    UUID        NOT NULL REFERENCES ledger_transaction (id),
    refund_transaction_id  UUID        REFERENCES ledger_transaction (id),
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX airtime_purchase_customer_idx ON airtime_purchase (customer_id, created_at DESC);
CREATE INDEX airtime_purchase_hold_idx ON airtime_purchase (hold_transaction_id);
CREATE INDEX airtime_purchase_refund_idx ON airtime_purchase (refund_transaction_id);
