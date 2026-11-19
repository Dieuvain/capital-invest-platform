-- Demander de l'argent : un client demande un montant à un autre, qui paie (avec son PIN) ou refuse.
-- Le paiement est un envoi ordinaire : mêmes frais, mêmes plafonds, même reçu.

CREATE TABLE money_request (
    id               UUID PRIMARY KEY,
    requester_id     UUID        NOT NULL REFERENCES customer (id),
    payer_id         UUID        NOT NULL REFERENCES customer (id),
    -- Clé d'idempotence du demandeur : un double appui ne crée qu'une demande.
    idempotency_key  TEXT        NOT NULL,
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)     NOT NULL,
    note             TEXT,
    -- EXPIRED n'est jamais écrit : une demande PENDING dont expires_at est passé est expirée.
    status           TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'DECLINED', 'CANCELLED')),
    transaction_id   UUID        REFERENCES ledger_transaction (id),
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    decided_at       TIMESTAMPTZ,
    CONSTRAINT money_request_two_customers CHECK (requester_id <> payer_id),
    CONSTRAINT money_request_key UNIQUE (requester_id, idempotency_key)
);

CREATE INDEX money_request_payer_idx ON money_request (payer_id, created_at DESC);
CREATE INDEX money_request_requester_idx ON money_request (requester_id, created_at DESC);
