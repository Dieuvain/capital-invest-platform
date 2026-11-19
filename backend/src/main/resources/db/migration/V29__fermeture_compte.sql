-- Fermeture du compte par le client. Le numéro reste attaché au compte fermé : les opérations
-- et le dossier KYC doivent être conservés, et un nouveau compte sur ce numéro passe par l'agence.
ALTER TABLE customer
    ADD COLUMN closed_at       TIMESTAMPTZ,
    ADD COLUMN closure_reason  TEXT;
