-- Contacts favoris : un destinataire et le surnom que le client lui donne (« Maman », « Loyer »).
CREATE TABLE favorite_recipient (
    id            UUID        PRIMARY KEY,
    customer_id   UUID        NOT NULL REFERENCES customer (id),
    recipient_id  UUID        NOT NULL REFERENCES customer (id),
    nickname      TEXT        NOT NULL CHECK (length(nickname) BETWEEN 1 AND 30),
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT favorite_not_self CHECK (customer_id <> recipient_id),
    CONSTRAINT favorite_unique UNIQUE (customer_id, recipient_id)
);
