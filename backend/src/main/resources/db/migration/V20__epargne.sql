-- Épargne : un second compte du client dans le grand livre, à côté de son portefeuille.
-- L'argent mis de côté ne sort pas de Capital Invest : il passe d'un compte du client à l'autre, sans frais.

-- Un seul compte épargne par client et par devise, même si deux premiers dépôts arrivent en même temps.
CREATE UNIQUE INDEX ledger_account_savings_uidx ON ledger_account (customer_id, currency) WHERE type = 'CUSTOMER_SAVINGS';

-- Objectif facultatif : un nom (« Rentrée scolaire ») et un montant à atteindre.
CREATE TABLE savings_goal (
    customer_id   UUID PRIMARY KEY REFERENCES customer (id),
    label         TEXT        NOT NULL CHECK (length(label) BETWEEN 1 AND 40),
    target_minor  BIGINT      NOT NULL CHECK (target_minor > 0),
    updated_at    TIMESTAMPTZ NOT NULL
);
