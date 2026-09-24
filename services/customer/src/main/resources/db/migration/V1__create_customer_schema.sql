-- Customer service schema. UUIDs, audit timestamps and versions are supplied by Hibernate.
-- Enums use VARCHAR with checks to match @Enumerated(EnumType.STRING).
-- Keycloak identifiers are external references, not foreign keys to Keycloak tables.

CREATE TABLE customer (
    id UUID PRIMARY KEY,
    keycloak_user_id UUID NOT NULL,
    customer_number VARCHAR(255) NOT NULL,
    first_name VARCHAR(255) NOT NULL,
    middle_name VARCHAR(255),
    last_name VARCHAR(255) NOT NULL,
    date_of_birth DATE NOT NULL,
    gender VARCHAR(255),
    nationality CHAR(2) NOT NULL,
    customer_status VARCHAR(255) NOT NULL,
    kyc_status VARCHAR(255) NOT NULL,
    kyc_tier VARCHAR(255) NOT NULL,
    wallet_eligible BOOLEAN NOT NULL,
    preferred_language VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_customer_keycloak_user_id UNIQUE (keycloak_user_id),
    CONSTRAINT uk_customer_customer_number UNIQUE (customer_number),
    CONSTRAINT ck_customer_customer_status CHECK (customer_status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_customer_kyc_status CHECK (kyc_status IN ('NOT_STARTED', 'PENDING', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_customer_kyc_tier CHECK (kyc_tier IN ('TIER_0', 'TIER_1', 'TIER_2', 'TIER_3'))
);

CREATE TABLE customer_contact (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    contact_type VARCHAR(255) NOT NULL,
    contact_value VARCHAR(255) NOT NULL,
    is_primary BOOLEAN NOT NULL,
    is_verified BOOLEAN NOT NULL,
    verified_at TIMESTAMPTZ,
    verification_source VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_customer_contact_customer_id FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_customer_contact_contact_type CHECK (contact_type IN ('EMAIL', 'PHONE')),
    CONSTRAINT ck_customer_contact_verification_source CHECK (verification_source IN ('OTP', 'KEYCLOAK', 'MANUAL')),
    CONSTRAINT uk_customer_contact_type_value UNIQUE (contact_type, contact_value)
);

CREATE TABLE customer_address (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    address_type VARCHAR(255) NOT NULL,
    country_code CHAR(2) NOT NULL,
    county VARCHAR(255) NOT NULL,
    city_or_town VARCHAR(255) NOT NULL,
    postal_code VARCHAR(255),
    address_line_1 VARCHAR(255) NOT NULL,
    address_line_2 VARCHAR(255),
    is_primary BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_customer_address_customer_id FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_customer_address_address_type CHECK (address_type IN ('HOME', 'WORK', 'OTHER'))
);

CREATE TABLE customer_consent (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    consent_type VARCHAR(255) NOT NULL,
    document_version VARCHAR(255) NOT NULL,
    accepted BOOLEAN NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    ip_address INET,
    user_agent VARCHAR(255),
    withdrawn_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_customer_consent_customer_id FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_customer_consent_consent_type CHECK (consent_type IN ('TERMS_AND_CONDITIONS', 'PRIVACY_POLICY', 'MARKETING'))
);

CREATE TABLE customer_limit (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    transaction_type VARCHAR(255) NOT NULL,
    currency CHAR(3) NOT NULL,
    per_transaction_limit NUMERIC NOT NULL,
    daily_limit NUMERIC NOT NULL,
    monthly_limit NUMERIC NOT NULL,
    daily_count_limit INTEGER,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    reason VARCHAR(255) NOT NULL,
    created_by_keycloak_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_customer_limit_customer_id FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_customer_limit_transaction_type CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PAYMENT'))
);

CREATE TABLE kyc_profile (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    status VARCHAR(255) NOT NULL,
    requested_tier VARCHAR(255) NOT NULL,
    approved_tier VARCHAR(255),
    occupation VARCHAR(255),
    employer_name VARCHAR(255),
    source_of_funds VARCHAR(255) NOT NULL,
    expected_monthly_volume NUMERIC,
    pep_status VARCHAR(255) NOT NULL,
    sanctions_status VARCHAR(255) NOT NULL,
    risk_rating VARCHAR(255) NOT NULL,
    submitted_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    reviewed_by_keycloak_id UUID,
    rejection_reason VARCHAR(255),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_kyc_profile_customer_id UNIQUE (customer_id),
    CONSTRAINT fk_kyc_profile_customer_id FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_kyc_profile_status CHECK (status IN ('NOT_STARTED', 'PENDING', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_kyc_profile_requested_tier CHECK (requested_tier IN ('TIER_0', 'TIER_1', 'TIER_2', 'TIER_3')),
    CONSTRAINT ck_kyc_profile_approved_tier CHECK (approved_tier IN ('TIER_0', 'TIER_1', 'TIER_2', 'TIER_3')),
    CONSTRAINT ck_kyc_profile_source_of_funds CHECK (source_of_funds IN ('SALARY', 'BUSINESS', 'SAVINGS', 'INVESTMENT', 'OTHER')),
    CONSTRAINT ck_kyc_profile_pep_status CHECK (pep_status IN ('NOT_SCREENED', 'NOT_PEP', 'PEP', 'UNDER_REVIEW')),
    CONSTRAINT ck_kyc_profile_sanctions_status CHECK (sanctions_status IN ('NOT_SCREENED', 'CLEAR', 'POTENTIAL_MATCH', 'CONFIRMED_MATCH')),
    CONSTRAINT ck_kyc_profile_risk_rating CHECK (risk_rating IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE TABLE kyc_document (
    id UUID PRIMARY KEY,
    kyc_profile_id UUID NOT NULL,
    document_number_hash VARCHAR(255) NOT NULL,
    document_type VARCHAR(255) NOT NULL,
    document_number_encrypted TEXT NOT NULL,
    issuing_country CHAR(2) NOT NULL,
    issued_at DATE,
    expires_at DATE,
    front_file_reference VARCHAR(255) NOT NULL,
    back_file_reference VARCHAR(255),
    verification_status VARCHAR(255) NOT NULL,
    verification_provider VARCHAR(255),
    provider_reference VARCHAR(255),
    failure_reason VARCHAR(255),
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_kyc_document_kyc_profile_id FOREIGN KEY (kyc_profile_id) REFERENCES kyc_profile (id),
    CONSTRAINT uk_kyc_document_document_number_hash UNIQUE (document_number_hash),
    CONSTRAINT ck_kyc_document_document_type CHECK (document_type IN ('NATIONAL_ID', 'PASSPORT', 'DRIVING_LICENSE', 'RESIDENCE_PERMIT')),
    CONSTRAINT ck_kyc_document_verification_status CHECK (verification_status IN ('PENDING', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED', 'EXPIRED'))
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    status VARCHAR(255) NOT NULL,
    attempt_count INTEGER NOT NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT fk_outbox_event_customer_id FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Foreign-key lookups; the unique KYC customer key already supplies its index.
CREATE INDEX idx_customer_contact_customer_id ON customer_contact (customer_id);
CREATE INDEX idx_customer_address_customer_id ON customer_address (customer_id);
CREATE INDEX idx_customer_consent_customer_id ON customer_consent (customer_id);
CREATE INDEX idx_customer_limit_customer_id ON customer_limit (customer_id);
CREATE INDEX idx_kyc_document_kyc_profile_id ON kyc_document (kyc_profile_id);
CREATE INDEX idx_outbox_event_customer_id ON outbox_event (customer_id);
CREATE INDEX idx_outbox_event_status_created_at ON outbox_event (status, created_at);
