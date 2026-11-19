-- Surveillance des marchands : une alerte concerne soit un client, soit un marchand.

ALTER TABLE monitoring_alert ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE monitoring_alert ADD COLUMN merchant_id UUID REFERENCES merchant (id);
ALTER TABLE monitoring_alert ADD CONSTRAINT monitoring_alert_one_subject CHECK ((customer_id IS NULL) <> (merchant_id IS NULL));

-- Une seule alerte ouverte par marchand et par règle, comme pour les clients.
CREATE UNIQUE INDEX monitoring_alert_open_merchant_idx ON monitoring_alert (merchant_id, rule) WHERE status = 'OPEN' AND merchant_id IS NOT NULL;
CREATE INDEX monitoring_alert_merchant_idx ON monitoring_alert (merchant_id, created_at DESC) WHERE merchant_id IS NOT NULL;
