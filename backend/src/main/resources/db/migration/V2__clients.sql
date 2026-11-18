CREATE TABLE customer (
    id          UUID PRIMARY KEY,
    phone       TEXT        NOT NULL UNIQUE,     -- format E.164, ex. +243970000000
    full_name   TEXT        NOT NULL,
    language    TEXT        NOT NULL DEFAULT 'fr' CHECK (language IN ('fr', 'sw', 'ln')),
    kyc_level   SMALLINT    NOT NULL DEFAULT 1 CHECK (kyc_level IN (1, 2)),
    status      TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE ledger_account
    ADD CONSTRAINT ledger_account_customer_fk FOREIGN KEY (customer_id) REFERENCES customer (id);
