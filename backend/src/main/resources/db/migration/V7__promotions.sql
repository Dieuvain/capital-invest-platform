-- Promotions affichées dans le bandeau défilant de l'accueil de l'app.
-- Gérées depuis le back-office par un administrateur ou un agent marketing.

ALTER TABLE backoffice_agent DROP CONSTRAINT backoffice_agent_role_check;
ALTER TABLE backoffice_agent ADD CONSTRAINT backoffice_agent_role_check
    CHECK (role IN ('KYC_AGENT', 'COMPLIANCE', 'ADMIN', 'MARKETING'));

CREATE TABLE promotion (
    id          UUID        PRIMARY KEY,
    title       TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 60),
    body        TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 140),
    cta_label   TEXT        CHECK (length(cta_label) <= 30),
    -- Ce que fait le bouton dans l'app. NULL : simple information.
    action      TEXT        CHECK (action IN ('KYC', 'TOPUP', 'TRANSFER', 'LIMITS')),
    style       TEXT        NOT NULL DEFAULT 'OCHRE' CHECK (style IN ('OCHRE', 'INK', 'GREEN', 'SKY')),
    -- Qui la voit : tout le monde, ou seulement un niveau KYC.
    audience    TEXT        NOT NULL DEFAULT 'ALL' CHECK (audience IN ('ALL', 'KYC1', 'KYC2')),
    priority    INT         NOT NULL DEFAULT 0,
    starts_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at     TIMESTAMPTZ,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at IS NULL OR ends_at > starts_at),
    CHECK ((cta_label IS NULL) = (action IS NULL))
);

CREATE INDEX promotion_live_idx ON promotion (priority DESC, starts_at DESC) WHERE active;

-- Premières promotions, modifiables ou désactivables depuis le back-office.
INSERT INTO promotion (id, title, body, cta_label, action, style, audience, priority) VALUES
    ('7b0d6a1e-5c55-4f7e-9a51-000000000001', 'Passez au niveau 2',
     'Envoyez jusqu''à 4 000 $ par opération avec votre pièce d''identité et un selfie.',
     'Vérifier mon identité', 'KYC', 'OCHRE', 'KYC1', 30),
    ('7b0d6a1e-5c55-4f7e-9a51-000000000002', 'Rechargez en quelques secondes',
     'M-Pesa, Airtel Money ou Orange Money : validez sur votre téléphone, c''est crédité.',
     'Recharger', 'TOPUP', 'GREEN', 'ALL', 20),
    ('7b0d6a1e-5c55-4f7e-9a51-000000000003', 'Envoyez à vos proches',
     'Un numéro, un montant, votre PIN. Les frais sont affichés avant de confirmer.',
     'Envoyer', 'TRANSFER', 'INK', 'ALL', 10);
