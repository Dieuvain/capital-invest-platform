-- Reversement aux marchands : l'argent dû au marchand part vers son compte mobile money.
--
-- C'est un retrait comme celui d'un client, depuis le compte du marchand : mêmes garanties
-- (fonds bloqués avant l'appel à l'opérateur, remboursement seulement sur échec certain, doute
-- tranché par un agent, rapprochement de nuit). La table withdrawal sert donc aux deux.

ALTER TABLE merchant ADD COLUMN payout_operator TEXT CHECK (payout_operator IN ('MPESA', 'AIRTEL', 'ORANGE'));
-- Numéro mobile money qui reçoit les reversements, au format international sans « + ».
ALTER TABLE merchant ADD COLUMN payout_msisdn TEXT;
-- Dernier changement du compte de reversement : les reversements attendent un délai après un changement.
ALTER TABLE merchant ADD COLUMN payout_account_changed_at TIMESTAMPTZ;

ALTER TABLE withdrawal ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE withdrawal ADD COLUMN merchant_id UUID REFERENCES merchant (id);
ALTER TABLE withdrawal ADD COLUMN requested_by UUID REFERENCES backoffice_agent (id);
ALTER TABLE withdrawal ADD CONSTRAINT withdrawal_one_owner CHECK ((customer_id IS NULL) <> (merchant_id IS NULL));
ALTER TABLE withdrawal ADD CONSTRAINT withdrawal_merchant_key UNIQUE (merchant_id, idempotency_key);
CREATE INDEX withdrawal_merchant_idx ON withdrawal (merchant_id, created_at DESC) WHERE merchant_id IS NOT NULL;
