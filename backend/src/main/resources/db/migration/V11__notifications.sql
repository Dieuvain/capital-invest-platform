-- Notifications au client : une ligne par événement, écrite dans la même transaction que le mouvement
-- d'argent (rien n'est perdu si le serveur s'arrête), puis envoyée par SMS avec relances.
-- La même ligne alimente la boîte de notifications de l'app.

CREATE TABLE notification (
    id               UUID        PRIMARY KEY,
    customer_id      UUID        NOT NULL REFERENCES customer (id),
    -- Un même événement ne produit jamais deux notifications (rejeu d'une opération, double appel).
    dedupe_key       TEXT        NOT NULL UNIQUE,
    kind             TEXT        NOT NULL,
    title            TEXT        NOT NULL,
    body             TEXT        NOT NULL,
    transaction_id   UUID        REFERENCES ledger_transaction (id),
    -- Texte court, sans accents (un SMS accentué coûte deux fois plus cher). NULL : pas de SMS.
    sms_text         TEXT        CHECK (sms_text IS NULL OR length(sms_text) <= 160),
    sms_status       TEXT        NOT NULL CHECK (sms_status IN ('NONE', 'PENDING', 'SENT', 'FAILED')),
    sms_attempts     INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    sms_sent_at      TIMESTAMPTZ,
    last_error       TEXT,
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX notification_customer_idx ON notification (customer_id, created_at DESC);
CREATE INDEX notification_sms_due_idx ON notification (next_attempt_at) WHERE sms_status = 'PENDING';
