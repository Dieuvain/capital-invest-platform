-- Vérification du destinataire avant un envoi : chaque recherche est comptée,
-- pour qu'un compte ne puisse pas parcourir les numéros et collecter les noms des clients.
CREATE TABLE recipient_lookup (
    customer_id  UUID        NOT NULL REFERENCES customer (id),
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX recipient_lookup_idx ON recipient_lookup (customer_id, created_at);
