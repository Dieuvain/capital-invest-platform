-- Reversement automatique : chaque nuit, ce que Capital Invest doit au marchand part vers son compte
-- mobile money, sans qu'un agent ait à le demander. Un administrateur peut l'arrêter marchand par marchand.
ALTER TABLE merchant ADD COLUMN auto_payout BOOLEAN NOT NULL DEFAULT TRUE;
