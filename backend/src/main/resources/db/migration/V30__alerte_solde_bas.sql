-- Alerte de solde bas : le client choisit un seuil ; il est prévenu une fois quand le solde passe dessous,
-- puis de nouveau seulement après être repassé au-dessus (pas un SMS à chaque achat).
ALTER TABLE customer
    ADD COLUMN low_balance_minor    BIGINT CHECK (low_balance_minor > 0),
    ADD COLUMN low_balance_alerted  BOOLEAN NOT NULL DEFAULT false;
