-- Passage au KYC 2 : pièces chiffrées, demande du client, décision d'un agent, journal d'audit.

CREATE TABLE kyc_document (
    id           UUID PRIMARY KEY,
    customer_id  UUID        NOT NULL REFERENCES customer (id),
    kind         TEXT        NOT NULL CHECK (kind IN ('ID_FRONT', 'ID_BACK', 'SELFIE')),
    content_type TEXT        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    size_bytes   INTEGER     NOT NULL,
    sha256       TEXT        NOT NULL,          -- empreinte du fichier en clair, pour l'audit
    encrypted    BYTEA       NOT NULL,          -- AES-256-GCM : IV (12 octets) puis texte chiffré
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX kyc_document_customer_idx ON kyc_document (customer_id, kind, created_at DESC);

CREATE TABLE kyc_request (
    id                UUID PRIMARY KEY,
    customer_id       UUID        NOT NULL REFERENCES customer (id),
    id_type           TEXT        NOT NULL CHECK (id_type IN ('VOTER_CARD', 'PASSPORT')),
    id_number         TEXT        NOT NULL,
    birth_date        DATE        NOT NULL,
    id_front_doc      UUID        NOT NULL REFERENCES kyc_document (id),
    id_back_doc       UUID        REFERENCES kyc_document (id),
    selfie_doc        UUID        NOT NULL REFERENCES kyc_document (id),
    screening_result  TEXT        NOT NULL CHECK (screening_result IN ('CLEAR', 'HIT')),
    screening_detail  TEXT,
    status            TEXT        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by        UUID,
    decided_at        TIMESTAMPTZ,
    rejection_reason  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Une seule demande en attente par client.
CREATE UNIQUE INDEX kyc_request_one_pending ON kyc_request (customer_id) WHERE status = 'PENDING';
CREATE INDEX kyc_request_status_idx ON kyc_request (status, created_at);

CREATE TABLE backoffice_agent (
    id             UUID PRIMARY KEY,
    email          TEXT        NOT NULL UNIQUE,
    full_name      TEXT        NOT NULL,
    password_hash  TEXT        NOT NULL,
    role           TEXT        NOT NULL CHECK (role IN ('KYC_AGENT', 'COMPLIANCE', 'ADMIN')),
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    failed_attempts INTEGER    NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_session (
    token_hash    TEXT PRIMARY KEY,
    agent_id      UUID        NOT NULL REFERENCES backoffice_agent (id),
    created_at    TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ
);

-- Qui a fait quoi, quand. Ajout seul, comme le grand livre.
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    actor_type  TEXT        NOT NULL CHECK (actor_type IN ('AGENT', 'CUSTOMER', 'SYSTEM')),
    actor_id    UUID,
    action      TEXT        NOT NULL,
    target_type TEXT        NOT NULL,
    target_id   UUID        NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id, created_at);

CREATE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log est en ajout seul (op: %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
