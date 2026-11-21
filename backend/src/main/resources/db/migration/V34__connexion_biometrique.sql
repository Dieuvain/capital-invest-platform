-- Connexion par empreinte (ou visage) : le téléphone garde une clé secrète, protégée par la biométrie du téléphone.
-- Le serveur n'en garde que l'empreinte SHA-256. Une clé vaut pour un client sur un téléphone ; le PIN reste
-- toujours possible, et reste exigé pour activer la clé et pour confirmer chaque envoi d'argent.

CREATE TABLE biometric_key (
    id            UUID PRIMARY KEY,
    customer_id   UUID        NOT NULL REFERENCES customer (id),
    device_id     TEXT        NOT NULL,
    key_hash      TEXT        NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL,
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);

-- Une seule clé active par client et par téléphone : réactiver l'empreinte remplace l'ancienne clé.
CREATE UNIQUE INDEX biometric_key_active_idx ON biometric_key (customer_id, device_id) WHERE revoked_at IS NULL;
