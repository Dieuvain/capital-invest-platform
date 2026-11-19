-- Crédit téléphone et factures sans réponse sûre : relance automatique, puis décision d'un agent après 24 h.

ALTER TABLE airtime_purchase DROP CONSTRAINT airtime_purchase_status_check;
ALTER TABLE airtime_purchase ADD CONSTRAINT airtime_purchase_status_check CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'REVIEW'));
ALTER TABLE airtime_purchase
    ADD COLUMN checks         INT NOT NULL DEFAULT 0,
    ADD COLUMN next_check_at  TIMESTAMPTZ,
    ADD COLUMN review_reason  TEXT;
CREATE INDEX airtime_purchase_to_check_idx ON airtime_purchase (next_check_at) WHERE status = 'PENDING';

ALTER TABLE bill_payment DROP CONSTRAINT bill_payment_status_check;
ALTER TABLE bill_payment ADD CONSTRAINT bill_payment_status_check CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REVIEW'));
ALTER TABLE bill_payment
    ADD COLUMN checks         INT NOT NULL DEFAULT 0,
    ADD COLUMN next_check_at  TIMESTAMPTZ,
    ADD COLUMN review_reason  TEXT;
CREATE INDEX bill_payment_to_check_idx ON bill_payment (next_check_at) WHERE status = 'PENDING';

-- Achats déjà en attente au moment de la migration : relancés tout de suite.
UPDATE airtime_purchase SET next_check_at = now() WHERE status = 'PENDING';
UPDATE bill_payment SET next_check_at = now() WHERE status = 'PENDING';
