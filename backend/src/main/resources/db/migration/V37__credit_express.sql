-- Crédit express : le client demande un petit crédit dans l'app, le serveur calcule un score et un plafond,
-- verse l'argent sur le portefeuille et prélève les échéances. Voir l'analyse UML : séquence 3.4,
-- diagramme d'états 4.3 et activité 5.1.
--
-- Comptabilité de caisse pour les intérêts : le grand livre ne connaît que le capital prêté
-- (créance sur les clients) ; les intérêts deviennent des revenus quand ils sont encaissés.

-- Créance sur les clients : capital prêté et pas encore remboursé.
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000801', 'LOANS_RECEIVABLE_USD', 'LOANS_RECEIVABLE', 'USD', 'DEBIT', TRUE),
    ('00000000-0000-0000-0000-000000000802', 'INTEREST_REVENUE_USD', 'INTEREST_REVENUE', 'USD', 'CREDIT', TRUE),
    -- Crédits passés en perte par le comité : le capital qui ne reviendra pas.
    ('00000000-0000-0000-0000-000000000803', 'LOAN_LOSSES_USD', 'LOAN_LOSSES', 'USD', 'DEBIT', TRUE);

-- Réglages du crédit : des paramètres du back-office, pas des valeurs codées (analyse UML, 5.1).
-- Valeurs PROVISOIRES, à fixer par la direction générale et le comité de crédit.
CREATE TABLE credit_settings (
    id                      INT         PRIMARY KEY CHECK (id = 1),
    -- Score minimal (0 à 100) pour recevoir une offre.
    min_score               INT         NOT NULL CHECK (min_score BETWEEN 0 AND 100),
    -- Au-dessus de ce montant, un agent de crédit examine la demande avant le versement.
    review_threshold_minor  BIGINT      NOT NULL CHECK (review_threshold_minor > 0),
    min_amount_minor        BIGINT      NOT NULL CHECK (min_amount_minor > 0),
    max_amount_minor        BIGINT      NOT NULL CHECK (max_amount_minor >= min_amount_minor),
    -- Taux d'intérêt par mois, en points de base (250 = 2,5 % par mois), sur le capital restant dû.
    monthly_rate_bp         INT         NOT NULL CHECK (monthly_rate_bp BETWEEN 0 AND 1000),
    -- Durées proposées, en mois.
    durations_months        INT[]       NOT NULL,
    -- Part des entrées d'argent mensuelles moyennes que le plafond peut atteindre.
    ceiling_percent_of_inflow INT       NOT NULL CHECK (ceiling_percent_of_inflow BETWEEN 1 AND 200),
    -- Jours de retard sur la plus vieille échéance avant de passer en défaut.
    default_after_days      INT         NOT NULL CHECK (default_after_days > 0),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID
);

INSERT INTO credit_settings (id, min_score, review_threshold_minor, min_amount_minor, max_amount_minor, monthly_rate_bp,
                             durations_months, ceiling_percent_of_inflow, default_after_days)
VALUES (1, 40, 30000, 1000, 100000, 250, '{1,2,3,6}', 50, 30);

CREATE TABLE credit (
    id                    UUID PRIMARY KEY,
    -- Supprimé avec le client (les tests vident la table customer) ; en production un client n'est jamais supprimé.
    customer_id           UUID        NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    -- Diagramme d'états 4.3 : PENDING_REVIEW (montant au-dessus du seuil), REJECTED, ACTIVE, LATE,
    -- DEFAULTED, WRITTEN_OFF (passé en perte), REPAID (soldé).
    status                TEXT        NOT NULL CHECK (status IN ('PENDING_REVIEW', 'REJECTED', 'ACTIVE', 'LATE', 'DEFAULTED', 'WRITTEN_OFF', 'REPAID')),
    principal_minor       BIGINT      NOT NULL CHECK (principal_minor > 0),
    duration_months       INT         NOT NULL CHECK (duration_months BETWEEN 1 AND 24),
    monthly_rate_bp       INT         NOT NULL,
    -- Score, plafond et facteurs au moment de la demande : ce qui a justifié l'octroi.
    score                 INT         NOT NULL,
    ceiling_minor         BIGINT      NOT NULL,
    factors               JSONB       NOT NULL,
    idempotency_key       TEXT        NOT NULL,
    -- Écriture du versement (pas de clé étrangère : les tests vident ledger_transaction).
    disbursement_transaction_id UUID,
    decided_by            UUID,
    decision_note         TEXT,
    reschedule_count      INT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL,
    disbursed_at          TIMESTAMPTZ,
    decided_at            TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    UNIQUE (customer_id, idempotency_key)
);

-- Un seul crédit en cours par client (règle de l'analyse UML, 2.2), demande en examen comprise.
CREATE UNIQUE INDEX credit_one_open_per_customer ON credit (customer_id)
    WHERE status IN ('PENDING_REVIEW', 'ACTIVE', 'LATE', 'DEFAULTED');
CREATE INDEX credit_status ON credit (status, created_at);

CREATE TABLE credit_instalment (
    id              UUID PRIMARY KEY,
    credit_id       UUID        NOT NULL REFERENCES credit (id) ON DELETE CASCADE,
    number          INT         NOT NULL,
    due_date        DATE        NOT NULL,
    principal_minor BIGINT      NOT NULL CHECK (principal_minor >= 0),
    interest_minor  BIGINT      NOT NULL CHECK (interest_minor >= 0),
    -- Payé jusqu'ici : les intérêts d'abord, puis le capital.
    paid_minor      BIGINT      NOT NULL DEFAULT 0 CHECK (paid_minor >= 0 AND paid_minor <= principal_minor + interest_minor),
    -- CANCELLED : remplacée par un nouvel échéancier (rééchelonnement) ou par un remboursement anticipé.
    status          TEXT        NOT NULL CHECK (status IN ('UPCOMING', 'LATE', 'PAID', 'CANCELLED')),
    paid_at         TIMESTAMPTZ,
    -- SMS de rappel deux jours avant, puis SMS de retard : une seule fois chacun.
    reminder_sent   BOOLEAN     NOT NULL DEFAULT FALSE,
    late_notified   BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE (credit_id, number)
);
CREATE INDEX credit_instalment_due ON credit_instalment (due_date) WHERE status IN ('UPCOMING', 'LATE');

-- Agent de crédit : examine les demandes au-dessus du seuil, rééchelonne.
ALTER TABLE backoffice_agent DROP CONSTRAINT backoffice_agent_role_check;
ALTER TABLE backoffice_agent ADD CONSTRAINT backoffice_agent_role_check
    CHECK (role IN ('KYC_AGENT', 'COMPLIANCE', 'ADMIN', 'MARKETING', 'CREDIT_OFFICER'));
