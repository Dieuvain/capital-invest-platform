-- Plafond d'envoi journalier choisi par le client, sous celui de son niveau KYC. NULL : le plafond du niveau.
-- Une baisse s'applique tout de suite ; une hausse (ou le retour au plafond du niveau) attend 24 heures,
-- pour qu'un voleur qui connaît le PIN ne puisse pas vider le compte d'un coup.
ALTER TABLE customer
    ADD COLUMN daily_limit_minor          BIGINT CHECK (daily_limit_minor > 0),
    ADD COLUMN pending_daily_limit_minor  BIGINT CHECK (pending_daily_limit_minor > 0),
    -- Date d'effet de la hausse en attente ; NULL : aucune hausse en attente.
    ADD COLUMN pending_daily_limit_at     TIMESTAMPTZ;
