-- Cagnottes : un client collecte l'argent de plusieurs personnes (deuil, mariage, cotisation, projet commun).
-- Chaque cagnotte a son propre compte dans le grand livre, au nom de l'organisateur. L'argent y reste
-- jusqu'à la clôture, qui le verse sur le portefeuille de l'organisateur.

CREATE TABLE pot (
    id                     UUID PRIMARY KEY,
    owner_id               UUID        NOT NULL REFERENCES customer (id),
    -- Code à partager (6 caractères sans 0, O, 1 ni I, pour le dicter sans erreur).
    code                   TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-HJ-NP-Z2-9]{6}$'),
    title                  TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 40),
    description            TEXT        CHECK (length(description) <= 140),
    target_minor           BIGINT      CHECK (target_minor > 0),
    account_id             UUID        NOT NULL UNIQUE REFERENCES ledger_account (id),
    status                 TEXT        NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    created_at             TIMESTAMPTZ NOT NULL,
    closed_at              TIMESTAMPTZ,
    payout_transaction_id  UUID REFERENCES ledger_transaction (id)
);

CREATE INDEX pot_owner_idx ON pot (owner_id, created_at DESC);

CREATE TABLE pot_contribution (
    id              UUID PRIMARY KEY,
    pot_id          UUID        NOT NULL REFERENCES pot (id),
    contributor_id  UUID        NOT NULL REFERENCES customer (id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    message         TEXT        CHECK (length(message) <= 80),
    -- Le participant peut cacher son nom aux autres participants ; l'organisateur le voit toujours.
    hide_name       BOOLEAN     NOT NULL DEFAULT FALSE,
    transaction_id  UUID        NOT NULL UNIQUE REFERENCES ledger_transaction (id),
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX pot_contribution_pot_idx ON pot_contribution (pot_id, created_at DESC);
