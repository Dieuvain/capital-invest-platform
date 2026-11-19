-- Surveillance des opérations (lutte contre le blanchiment) : après chaque mouvement d'argent, des règles
-- simples repèrent les comportements à risque et ouvrent une alerte pour la conformité.

CREATE TABLE monitoring_alert (
    id              UUID PRIMARY KEY,
    customer_id     UUID        NOT NULL REFERENCES customer (id),
    -- VELOCITY, STRUCTURING, FAN_IN, RAPID_CASH_OUT
    rule            TEXT        NOT NULL,
    -- Ce qui a déclenché l'alerte, en clair pour l'agent.
    detail          TEXT        NOT NULL,
    -- L'opération qui a fait franchir le seuil.
    transaction_id  UUID        NOT NULL REFERENCES ledger_transaction (id),
    status          TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DISMISSED', 'ESCALATED')),
    created_at      TIMESTAMPTZ NOT NULL,
    resolved_by     UUID        REFERENCES backoffice_agent (id),
    resolved_at     TIMESTAMPTZ,
    resolution_note TEXT
);

-- Une seule alerte ouverte par client et par règle : la suivante attend que la première soit traitée.
CREATE UNIQUE INDEX monitoring_alert_open_idx ON monitoring_alert (customer_id, rule) WHERE status = 'OPEN';
CREATE INDEX monitoring_alert_status_idx ON monitoring_alert (status, created_at DESC);
CREATE INDEX monitoring_alert_customer_idx ON monitoring_alert (customer_id, created_at DESC);
