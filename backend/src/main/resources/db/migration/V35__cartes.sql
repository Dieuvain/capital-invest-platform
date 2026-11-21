-- Cartes prépayées Visa ou Mastercard, virtuelle et physique, émises par le processeur de cartes.
-- Le numéro complet et le cryptogramme ne sont jamais stockés ici : seulement le jeton du processeur
-- et les 4 derniers chiffres. La carte dépense le portefeuille USD du client.

-- Compte de règlement du processeur : ce que Capital Invest lui doit pour les paiements par carte acceptés.
INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000601', 'CARD_SETTLEMENT_USD', 'OPERATOR_SETTLEMENT', 'USD', 'DEBIT', TRUE);

CREATE TABLE card (
    id                  UUID PRIMARY KEY,
    -- Supprimé avec le client (les tests vident la table customer) ; en production un client n'est jamais supprimé.
    customer_id         UUID        NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    kind                TEXT        NOT NULL CHECK (kind IN ('VIRTUAL', 'PHYSICAL')),
    -- Cycle de vie : voir l'analyse UML, diagramme d'états 4.1.
    status              TEXT        NOT NULL CHECK (status IN ('ORDERED', 'IN_PRODUCTION', 'READY_FOR_PICKUP', 'ACTIVE', 'FROZEN', 'BLOCKED', 'EXPIRED', 'DELETED')),
    processor           TEXT        NOT NULL,
    processor_token     TEXT        NOT NULL,
    network             TEXT        NOT NULL CHECK (network IN ('VISA', 'MASTERCARD')),
    last4               TEXT        NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
    expiry_month        INT         NOT NULL CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year         INT         NOT NULL,
    -- Nom gravé ou affiché sur la carte.
    embossed_name       TEXT        NOT NULL,
    daily_limit_minor   BIGINT      NOT NULL CHECK (daily_limit_minor > 0),
    online_payments     BOOLEAN     NOT NULL,
    -- Carte physique : agence où le client la retire.
    pickup_branch       TEXT,
    block_reason        TEXT        CHECK (block_reason IN ('LOST', 'STOLEN', 'FRAUD')),
    -- Carte physique qui en remplace une autre, bloquée ou expirée.
    replaces_card_id    UUID        UNIQUE REFERENCES card (id) ON DELETE CASCADE,
    -- Clé d'idempotence de la commande (carte physique) : la renvoyer après une coupure ne commande pas deux cartes.
    idempotency_key     TEXT,
    -- Frais payés pour cette carte (commande ou remplacement).
    fee_transaction_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL,
    activated_at        TIMESTAMPTZ,
    blocked_at          TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL,
    UNIQUE (processor, processor_token),
    UNIQUE (customer_id, idempotency_key),
    CHECK ((kind = 'PHYSICAL') = (pickup_branch IS NOT NULL)),
    CHECK (kind = 'PHYSICAL' OR status NOT IN ('ORDERED', 'IN_PRODUCTION', 'READY_FOR_PICKUP')),
    CHECK (kind = 'VIRTUAL' OR status <> 'DELETED')
);

CREATE INDEX card_customer_idx ON card (customer_id, created_at DESC);

-- Une seule carte vivante de chaque sorte par client : une carte bloquée, expirée ou supprimée ne compte plus.
CREATE UNIQUE INDEX card_one_live_per_kind ON card (customer_id, kind) WHERE status NOT IN ('BLOCKED', 'EXPIRED', 'DELETED');

-- Demandes d'autorisation reçues du processeur (paiement chez un commerçant, en magasin ou en ligne).
CREATE TABLE card_authorization (
    id                  UUID PRIMARY KEY,
    card_id             UUID        NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    -- Référence du processeur : la même demande renvoyée après une coupure ne débite pas deux fois.
    processor_reference TEXT        NOT NULL,
    amount_minor        BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3)     NOT NULL,
    merchant_name       TEXT        NOT NULL,
    merchant_city       TEXT,
    online              BOOLEAN     NOT NULL,
    status              TEXT        NOT NULL CHECK (status IN ('APPROVED', 'DECLINED', 'REVERSED')),
    decline_code        TEXT,
    -- Écritures du paiement et de son annulation. Pas de clé étrangère vers ledger_transaction :
    -- les tests vident le grand livre table par table.
    transaction_id      UUID,
    reversal_transaction_id UUID,
    created_at          TIMESTAMPTZ NOT NULL,
    reversed_at         TIMESTAMPTZ,
    UNIQUE (card_id, processor_reference),
    CHECK ((status = 'DECLINED') = (decline_code IS NOT NULL)),
    CHECK ((status = 'DECLINED') = (transaction_id IS NULL))
);

CREATE INDEX card_authorization_card_idx ON card_authorization (card_id, created_at DESC);
