-- Compte en francs congolais (CDF) à côté du compte en dollars, et change entre les deux.
-- Les montants CDF sont en francs entiers (le franc n'a plus de subdivision en circulation).

-- Un seul portefeuille par client et par devise.
CREATE UNIQUE INDEX ledger_account_wallet_uidx ON ledger_account (customer_id, currency) WHERE type = 'CUSTOMER_WALLET';

-- Chaque client existant reçoit son compte CDF, dans le même état que son compte USD.
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative, customer_id, status)
SELECT gen_random_uuid(), NULL, 'CUSTOMER_WALLET', 'CDF', 'CREDIT', FALSE, a.customer_id, a.status
FROM ledger_account a
WHERE a.type = 'CUSTOMER_WALLET' AND a.currency = 'USD'
ON CONFLICT DO NOTHING;

-- Position de change : ce que Capital Invest a acheté (solde positif) ou vendu (négatif) dans chaque devise.
-- La trésorerie la couvre en dehors de l'app (bureau de change, banque).
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000701', 'FX_POSITION_USD', 'FX_POSITION', 'USD', 'CREDIT', TRUE),
    ('00000000-0000-0000-0000-000000000702', 'FX_POSITION_CDF', 'FX_POSITION', 'CDF', 'CREDIT', TRUE),
    ('00000000-0000-0000-0000-000000000202', 'FEES_REVENUE_CDF', 'FEES_REVENUE', 'CDF', 'CREDIT', FALSE);

-- Détail de chaque change, pour le reçu : la transaction du grand livre a pour clé « exchange:<id> ».
CREATE TABLE currency_exchange (
    id              UUID PRIMARY KEY,
    customer_id     UUID        NOT NULL REFERENCES customer (id),
    from_currency   CHAR(3)     NOT NULL CHECK (from_currency IN ('USD', 'CDF')),
    to_currency     CHAR(3)     NOT NULL CHECK (to_currency IN ('USD', 'CDF')),
    -- Montant débité, frais compris, dans la devise de départ.
    from_minor      BIGINT      NOT NULL CHECK (from_minor > 0),
    -- Frais, dans la devise de départ.
    fee_minor       BIGINT      NOT NULL CHECK (fee_minor >= 0),
    -- Montant crédité, dans la devise d'arrivée.
    to_minor        BIGINT      NOT NULL CHECK (to_minor > 0),
    -- Francs pour un dollar, appliqué à ce change.
    rate            NUMERIC(14, 4) NOT NULL CHECK (rate > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_currency <> to_currency)
);

CREATE INDEX currency_exchange_customer_idx ON currency_exchange (customer_id, created_at);
