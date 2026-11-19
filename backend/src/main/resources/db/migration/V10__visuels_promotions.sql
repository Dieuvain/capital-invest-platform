-- Les promotions deviennent des visuels conçus par les graphistes (1200 x 520 px conseillé).
-- Le titre reste obligatoire : il est lu par le lecteur d'écran. Le texte devient facultatif,
-- il ne sert plus qu'à la carte de secours affichée tant qu'aucun visuel n'est envoyé.

ALTER TABLE promotion ALTER COLUMN body DROP NOT NULL;
ALTER TABLE promotion DROP CONSTRAINT promotion_body_check;
ALTER TABLE promotion ADD CONSTRAINT promotion_body_check CHECK (body IS NULL OR length(body) BETWEEN 1 AND 140);

-- Sur un visuel, toute l'image est cliquable : une action peut exister sans texte de bouton.
ALTER TABLE promotion DROP CONSTRAINT promotion_check1;
ALTER TABLE promotion ADD CONSTRAINT promotion_cta_needs_action CHECK (cta_label IS NULL OR action IS NOT NULL);

ALTER TABLE promotion
    ADD COLUMN image            BYTEA,
    ADD COLUMN image_type       TEXT CHECK (image_type IN ('image/jpeg', 'image/png', 'image/webp')),
    ADD COLUMN image_updated_at TIMESTAMPTZ,
    ADD CONSTRAINT promotion_image_complete CHECK ((image IS NULL) = (image_type IS NULL));
