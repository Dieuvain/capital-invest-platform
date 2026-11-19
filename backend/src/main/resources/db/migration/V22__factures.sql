-- Paiement de factures : électricité (SNEL, compteurs prépayés), eau (REGIDESO), télévision (Canal+, DStv).

-- Ce que Capital Invest doit aux facturiers (via l'agrégateur de factures) : chaque paiement le diminue.
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000402', 'BILLERS_USD', 'OPERATOR_SETTLEMENT', 'USD', 'DEBIT', TRUE);

CREATE TABLE bill_payment (
    id                     UUID PRIMARY KEY,
    customer_id            UUID        NOT NULL REFERENCES customer (id),
    idempotency_key        TEXT        NOT NULL,
    biller                 TEXT        NOT NULL,
    reference              TEXT        NOT NULL,      -- n° de compteur, d'abonné ou de décodeur
    holder_name            TEXT        NOT NULL,      -- titulaire chez le facturier, montré avant de payer
    amount_minor           BIGINT      NOT NULL CHECK (amount_minor > 0),
    status                 TEXT        NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    provider               TEXT        NOT NULL,
    provider_reference     TEXT,
    token                  TEXT,                      -- code à taper sur le compteur prépayé
    failure_message        TEXT,
    hold_transaction_id    UUID        NOT NULL REFERENCES ledger_transaction (id),
    refund_transaction_id  UUID        REFERENCES ledger_transaction (id),
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX bill_payment_customer_idx ON bill_payment (customer_id, created_at DESC);
CREATE INDEX bill_payment_hold_idx ON bill_payment (hold_transaction_id);
