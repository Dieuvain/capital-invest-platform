-- Déclaration de soupçon à la CENAREF : la conformité rédige un dossier (faits, analyse, opérations
-- de la période), l'imprime ou l'exporte, le transmet par le canal officiel, puis note la référence
-- reçue. Une fois transmis, le dossier ne change plus. Le client n'en est jamais informé.

CREATE TABLE suspicion_report (
    id                UUID PRIMARY KEY,
    customer_id       UUID        NOT NULL REFERENCES customer (id),
    -- Alerte de surveillance d'origine, s'il y en a une.
    alert_id          UUID        REFERENCES monitoring_alert (id),
    status            TEXT        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED')),
    period_from       DATE        NOT NULL,
    period_to         DATE        NOT NULL,
    -- Ce qui a été constaté, et pourquoi c'est suspect.
    facts             TEXT        NOT NULL DEFAULT '',
    analysis          TEXT        NOT NULL DEFAULT '',
    created_by        UUID        NOT NULL REFERENCES backoffice_agent (id),
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    submitted_by      UUID        REFERENCES backoffice_agent (id),
    submitted_at      TIMESTAMPTZ,
    -- Date de transmission à la CENAREF et référence reçue en retour.
    submitted_on      DATE,
    cenaref_reference TEXT,
    CONSTRAINT suspicion_report_period CHECK (period_to >= period_from),
    CONSTRAINT suspicion_report_submitted CHECK (status = 'DRAFT' OR (submitted_on IS NOT NULL AND cenaref_reference IS NOT NULL))
);

CREATE INDEX suspicion_report_customer_idx ON suspicion_report (customer_id, created_at DESC);
CREATE INDEX suspicion_report_status_idx ON suspicion_report (status, created_at DESC);
