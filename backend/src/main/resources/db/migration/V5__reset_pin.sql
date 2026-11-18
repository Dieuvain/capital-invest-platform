-- Date du dernier changement de PIN par code SMS. Pendant le délai de sécurité qui suit,
-- les envois d'argent sont bloqués : c'est la parade contre le vol de carte SIM.
ALTER TABLE customer ADD COLUMN pin_reset_at TIMESTAMPTZ;

-- Accélère le calcul des montants envoyés dans la journée.
CREATE INDEX topup_customer_status_idx ON topup (customer_id, status);
