CREATE SEQUENCE customer_public_number_seq;
ALTER TABLE customer ADD COLUMN preferred_name VARCHAR(255);
ALTER TABLE customer_consent ADD COLUMN channel VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN';

CREATE TABLE contact_verification_challenge (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customer(id),
    contact_id UUID NOT NULL, -- retained after contact deletion so account rate limits cannot be bypassed
    contact_fingerprint VARCHAR(64) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_contact_challenge_customer_time ON contact_verification_challenge(customer_id, created_at);
CREATE INDEX idx_contact_challenge_contact_time ON contact_verification_challenge(contact_id, created_at);
