-- Paiement chez un marchand par QR : le client scanne le code affiché en caisse et paie depuis son solde.
--
-- Le client ne paie aucun frais. Le marchand reçoit le montant moins une commission (sa « remise »),
-- sur son propre compte chez Capital Invest. Le reversement de ce compte vers sa banque ou son
-- mobile money viendra plus tard.

CREATE TABLE merchant (
    id              UUID PRIMARY KEY,
    -- Code court, imprimé sous le QR et saisissable à la main si l'appareil photo ne lit pas.
    code            TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Z2-9]{6}$'),
    name            TEXT        NOT NULL,
    category        TEXT,
    city            TEXT,
    -- Numéro qui reçoit un SMS à chaque paiement : c'est ce que le caissier vérifie.
    phone           TEXT        NOT NULL,
    -- En points de base : 100 = 1 %.
    commission_bps  INT         NOT NULL CHECK (commission_bps BETWEEN 0 AND 1000),
    status          TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    wallet_account_id UUID      NOT NULL UNIQUE REFERENCES ledger_account (id),
    created_by      UUID        REFERENCES backoffice_agent (id),
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE merchant_payment (
    id              UUID PRIMARY KEY,
    merchant_id     UUID        NOT NULL REFERENCES merchant (id),
    customer_id     UUID        NOT NULL REFERENCES customer (id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    commission_minor BIGINT     NOT NULL CHECK (commission_minor >= 0 AND commission_minor <= amount_minor),
    currency        CHAR(3)     NOT NULL,
    note            TEXT,
    transaction_id  UUID        NOT NULL UNIQUE REFERENCES ledger_transaction (id),
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX merchant_payment_merchant_idx ON merchant_payment (merchant_id, created_at DESC);
CREATE INDEX merchant_payment_customer_idx ON merchant_payment (customer_id, created_at);

-- Le marchand est prévenu par SMS, comme le client : une notification va à l'un ou à l'autre.
ALTER TABLE notification ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE notification ADD COLUMN merchant_id UUID REFERENCES merchant (id);
ALTER TABLE notification ADD CONSTRAINT notification_one_recipient CHECK ((customer_id IS NULL) <> (merchant_id IS NULL));
