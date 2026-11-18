CREATE TABLE topup (
    id                 UUID PRIMARY KEY,
    idempotency_key    TEXT        NOT NULL UNIQUE,
    customer_id        UUID        NOT NULL REFERENCES customer (id),
    operator           TEXT        NOT NULL CHECK (operator IN ('MPESA', 'AIRTEL', 'ORANGE')),
    amount_minor       BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency           CHAR(3)     NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    operator_reference TEXT,
    ledger_transaction_id UUID REFERENCES ledger_transaction (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX topup_customer_idx ON topup (customer_id, created_at DESC);
