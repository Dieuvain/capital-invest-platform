-- Retraits : le client renvoie de l'argent de son solde vers son compte mobile money.
--
-- Principe : l'argent est bloqué dès la demande (débité du portefeuille vers un compte d'attente),
-- avant même d'appeler l'opérateur. Il ne peut donc pas être dépensé deux fois.
-- Paiement confirmé chez l'opérateur : il quitte le compte d'attente vers le compte de règlement.
-- Échec certain : il revient au client, frais compris. Doute : un agent vérifie, rien n'est rendu au hasard.

CREATE TABLE withdrawal (
    id                    UUID PRIMARY KEY,
    idempotency_key       TEXT        NOT NULL,
    customer_id           UUID        NOT NULL REFERENCES customer (id),
    operator              TEXT        NOT NULL CHECK (operator IN ('MPESA', 'AIRTEL', 'ORANGE')),
    msisdn                TEXT        NOT NULL,
    amount_minor          BIGINT      NOT NULL CHECK (amount_minor > 0),
    fee_minor             BIGINT      NOT NULL CHECK (fee_minor >= 0),
    currency              CHAR(3)     NOT NULL,
    status                TEXT        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'REVIEW')),
    provider              TEXT        NOT NULL,
    settlement_code       TEXT        NOT NULL,
    provider_reference    TEXT,
    failure_code          TEXT,
    failure_message       TEXT,
    review_reason         TEXT,
    checks                INT         NOT NULL DEFAULT 0,
    next_check_at         TIMESTAMPTZ,
    last_checked_at       TIMESTAMPTZ,
    hold_transaction_id   UUID        NOT NULL REFERENCES ledger_transaction (id),
    final_transaction_id  UUID REFERENCES ledger_transaction (id),
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX withdrawal_customer_idx ON withdrawal (customer_id, created_at DESC);
CREATE INDEX withdrawal_to_check_idx ON withdrawal (next_check_at) WHERE status = 'PENDING';
CREATE UNIQUE INDEX withdrawal_provider_reference_idx ON withdrawal (provider, provider_reference) WHERE provider_reference IS NOT NULL;

-- Argent des retraits en cours : dû au client tant que l'opérateur n'a pas payé (passif).
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000301', 'PAYOUTS_IN_TRANSIT_USD', 'PAYOUT_IN_TRANSIT', 'USD', 'CREDIT', FALSE);

-- Les notifications et le rapprochement couvrent aussi les retraits.
ALTER TABLE mobile_money_event ADD COLUMN withdrawal_id UUID REFERENCES withdrawal (id);

ALTER TABLE reconciliation_item ALTER COLUMN topup_id DROP NOT NULL;
ALTER TABLE reconciliation_item ADD COLUMN withdrawal_id UUID REFERENCES withdrawal (id);
ALTER TABLE reconciliation_item ADD CONSTRAINT reconciliation_item_one_target
    CHECK ((topup_id IS NULL) <> (withdrawal_id IS NULL));
ALTER TABLE reconciliation_item ADD CONSTRAINT reconciliation_item_run_withdrawal_key UNIQUE (run_id, withdrawal_id);
ALTER TABLE reconciliation_item DROP CONSTRAINT reconciliation_item_kind_check;
ALTER TABLE reconciliation_item ADD CONSTRAINT reconciliation_item_kind_check CHECK (kind IN (
    'CONFIRMED_NOT_CREDITED',   -- recharge : l'opérateur a encaissé, le client n'a rien reçu
    'CREDITED_NOT_CONFIRMED',   -- recharge : le client a été crédité, l'opérateur n'a rien encaissé
    'PAID_BUT_REFUNDED',        -- retrait : l'opérateur a payé, et le client a aussi été remboursé
    'MARKED_PAID_NOT_PAID',     -- retrait : noté payé chez nous, l'opérateur n'a rien versé
    'AMOUNT_MISMATCH',
    'STILL_PENDING'));
