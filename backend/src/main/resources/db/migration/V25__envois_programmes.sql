-- Envois programmés : un envoi à un autre client, répété chaque semaine ou chaque mois (loyer, argent pour la famille).
-- Le client le confirme une fois par son PIN ; chaque envoi passe ensuite par les mêmes contrôles qu'un envoi normal.
CREATE TABLE scheduled_transfer (
    id               UUID PRIMARY KEY,
    customer_id      UUID        NOT NULL REFERENCES customer (id),
    recipient_id     UUID        NOT NULL REFERENCES customer (id),
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    note             TEXT        CHECK (length(note) <= 140),
    frequency        TEXT        NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY')),
    -- Jour de la semaine (1 = lundi … 7 = dimanche) ou du mois (1 à 31, ramené au dernier jour d'un mois plus court).
    day              INT         NOT NULL CHECK (day BETWEEN 1 AND 31),
    status           TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED')),
    next_run_at      TIMESTAMPTZ NOT NULL,
    last_run_at      TIMESTAMPTZ,
    last_result      TEXT        CHECK (last_result IN ('SENT', 'FAILED')),
    last_failure     TEXT,
    failures         INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    CHECK (customer_id <> recipient_id),
    CHECK (frequency = 'MONTHLY' OR day <= 7)
);

CREATE INDEX scheduled_transfer_due_idx ON scheduled_transfer (next_run_at) WHERE status = 'ACTIVE';
CREATE INDEX scheduled_transfer_customer_idx ON scheduled_transfer (customer_id);
