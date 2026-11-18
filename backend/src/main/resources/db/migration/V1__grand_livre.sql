-- Grand livre en partie double.
-- Le solde d'un compte n'est jamais stocké : il se calcule à partir des écritures.

CREATE TABLE ledger_account (
    id              UUID PRIMARY KEY,
    code            TEXT UNIQUE,                 -- comptes système : SETTLEMENT_MPESA_USD, ...
    type            TEXT        NOT NULL,        -- CUSTOMER_WALLET, OPERATOR_SETTLEMENT, FEES_REVENUE
    currency        CHAR(3)     NOT NULL CHECK (currency IN ('USD', 'CDF')),
    normal_side     TEXT        NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    allow_negative  BOOLEAN     NOT NULL DEFAULT FALSE,
    customer_id     UUID,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_account_customer_idx ON ledger_account (customer_id);

CREATE TABLE ledger_transaction (
    id               UUID PRIMARY KEY,
    idempotency_key  TEXT        NOT NULL UNIQUE,
    request_hash     TEXT        NOT NULL,
    type             TEXT        NOT NULL,       -- TOPUP, TRANSFER, ...
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entry (
    id              UUID PRIMARY KEY,
    transaction_id  UUID        NOT NULL REFERENCES ledger_transaction (id),
    account_id      UUID        NOT NULL REFERENCES ledger_account (id),
    direction       TEXT        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3)     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entry_account_idx ON ledger_entry (account_id, created_at);
CREATE INDEX ledger_entry_transaction_idx ON ledger_entry (transaction_id);

-- Les écritures sont immuables : une erreur se corrige par une contre-passation.
CREATE FUNCTION ledger_entry_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry est en ajout seul (op: %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_immutable();

-- Comptes de règlement des opérateurs mobile money (actifs : solde naturel au débit).
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000101', 'SETTLEMENT_MPESA_USD',  'OPERATOR_SETTLEMENT', 'USD', 'DEBIT', TRUE),
    ('00000000-0000-0000-0000-000000000102', 'SETTLEMENT_AIRTEL_USD', 'OPERATOR_SETTLEMENT', 'USD', 'DEBIT', TRUE),
    ('00000000-0000-0000-0000-000000000103', 'SETTLEMENT_ORANGE_USD', 'OPERATOR_SETTLEMENT', 'USD', 'DEBIT', TRUE),
    ('00000000-0000-0000-0000-000000000201', 'FEES_REVENUE_USD',      'FEES_REVENUE',        'USD', 'CREDIT', FALSE);
