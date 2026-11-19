-- Téléphone perdu ou volé : le client bloque lui-même son compte, depuis l'app ou depuis un autre téléphone.
-- Le compte passe en SUSPENDED ; cette date le distingue d'une suspension décidée par la conformité.
-- Seul un agent le débloque, après avoir vérifié la pièce d'identité du client.
ALTER TABLE customer ADD COLUMN blocked_by_customer_at TIMESTAMPTZ;
