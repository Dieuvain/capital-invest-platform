-- Branchement des vrais opérateurs mobile money.
--
-- Principe : une recharge n'est créditée qu'après avoir lu son statut final directement
-- chez le prestataire (M-Pesa, Airtel, Orange en direct, ou un agrégateur). Un webhook ne fait
-- que déclencher cette lecture ; son contenu n'est jamais cru tel quel.

-- Qui traite la recharge, depuis quel numéro, et sur quel compte de règlement elle arrive.
ALTER TABLE topup
    ADD COLUMN provider            TEXT NOT NULL DEFAULT 'SIMULATOR',
    ADD COLUMN msisdn              TEXT,
    ADD COLUMN settlement_code     TEXT,
    ADD COLUMN provider_reference  TEXT,
    ADD COLUMN failure_code        TEXT,
    ADD COLUMN failure_message     TEXT,
    ADD COLUMN checks              INT  NOT NULL DEFAULT 0,
    ADD COLUMN next_check_at       TIMESTAMPTZ,
    ADD COLUMN last_checked_at     TIMESTAMPTZ,
    ADD COLUMN review_reason       TEXT;

-- REVIEW : l'argent a peut-être bougé mais rien n'est sûr (montant différent, statut incertain trop longtemps).
-- Un agent tranche ; rien n'est crédité automatiquement.
ALTER TABLE topup DROP CONSTRAINT topup_status_check;
ALTER TABLE topup ADD CONSTRAINT topup_status_check CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'REVIEW'));

CREATE INDEX topup_to_check_idx ON topup (next_check_at) WHERE status = 'PENDING';
CREATE UNIQUE INDEX topup_provider_reference_idx ON topup (provider, provider_reference) WHERE provider_reference IS NOT NULL;

-- Compte de règlement de l'agrégateur : l'argent des trois opérateurs y arrive.
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000104', 'SETTLEMENT_PAWAPAY_USD', 'OPERATOR_SETTLEMENT', 'USD', 'DEBIT', TRUE);

-- Tout ce que les prestataires nous envoient, gardé tel quel comme preuve.
CREATE TABLE mobile_money_event (
    id              UUID PRIMARY KEY,
    provider        TEXT        NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    signature_valid BOOLEAN,
    topup_id        UUID REFERENCES topup (id),
    body            TEXT        NOT NULL,
    outcome         TEXT        NOT NULL
);
CREATE INDEX mobile_money_event_topup_idx ON mobile_money_event (topup_id, received_at);

-- Rapprochement quotidien : nos recharges comparées au statut chez le prestataire.
CREATE TABLE reconciliation_run (
    id            UUID PRIMARY KEY,
    business_date DATE        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    checked       INT         NOT NULL DEFAULT 0,
    matched       INT         NOT NULL DEFAULT 0,
    discrepancies INT         NOT NULL DEFAULT 0,
    UNIQUE (business_date)
);

CREATE TABLE reconciliation_item (
    id              UUID PRIMARY KEY,
    run_id          UUID        NOT NULL REFERENCES reconciliation_run (id),
    topup_id        UUID        NOT NULL REFERENCES topup (id),
    kind            TEXT        NOT NULL CHECK (kind IN (
                        'CONFIRMED_NOT_CREDITED',   -- l'opérateur a encaissé, le client n'a rien reçu
                        'CREDITED_NOT_CONFIRMED',   -- le client a été crédité, l'opérateur n'a rien encaissé
                        'AMOUNT_MISMATCH',
                        'STILL_PENDING')),
    our_status      TEXT        NOT NULL,
    provider_status TEXT        NOT NULL,
    our_amount_minor      BIGINT NOT NULL,
    provider_amount_minor BIGINT,
    resolved_at     TIMESTAMPTZ,
    resolved_by     UUID REFERENCES backoffice_agent (id),
    resolution      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, topup_id)
);
CREATE INDEX reconciliation_item_open_idx ON reconciliation_item (created_at) WHERE resolved_at IS NULL;
