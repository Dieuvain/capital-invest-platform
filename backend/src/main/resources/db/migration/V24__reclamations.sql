-- Réclamations des clients : sur une opération (depuis son reçu) ou sur le compte en général.
-- Chaque réclamation a une échéance de réponse ; la réponse de l'agent est gardée avec son nom et sa conclusion.
CREATE TABLE complaint (
    id              UUID PRIMARY KEY,
    customer_id     UUID        NOT NULL REFERENCES customer (id),
    transaction_id  UUID        REFERENCES ledger_transaction (id),
    category        TEXT        NOT NULL CHECK (category IN ('NOT_RECEIVED', 'WRONG_AMOUNT', 'NOT_ME', 'OTHER')),
    message         TEXT        NOT NULL CHECK (length(message) BETWEEN 1 AND 1000),
    status          TEXT        NOT NULL CHECK (status IN ('OPEN', 'ANSWERED')),
    due_at          TIMESTAMPTZ NOT NULL,
    answer          TEXT,
    outcome         TEXT        CHECK (outcome IN ('FOUNDED', 'UNFOUNDED')),
    answered_by     UUID        REFERENCES backoffice_agent (id),
    answered_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'OPEN') = (answer IS NULL)),
    CHECK ((status = 'OPEN') = (outcome IS NULL))
);

-- Une seule réclamation ouverte par client et par opération (l'envoyeur et le destinataire peuvent chacun en ouvrir une).
CREATE UNIQUE INDEX complaint_open_tx_uidx ON complaint (customer_id, transaction_id) WHERE status = 'OPEN';
CREATE INDEX complaint_customer_idx ON complaint (customer_id, created_at DESC);
CREATE INDEX complaint_open_idx ON complaint (due_at) WHERE status = 'OPEN';
