-- Sécurité du compte : chaque session reçoit un identifiant public (pour la déconnecter sans exposer
-- l'empreinte du jeton) et le nom du téléphone, affiché au client dans « Connexions récentes ».

ALTER TABLE session
    ADD COLUMN id          UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN device_name TEXT;

CREATE UNIQUE INDEX session_id_idx ON session (id);
CREATE INDEX session_customer_device_idx ON session (customer_id, device_id);
