CREATE TABLE payment
(
    id                UUID                           NOT NULL,
    public_id         UUID                           NOT NULL,
    reference         VARCHAR(50)                    NOT NULL,
    customer_id       UUID                           NOT NULL,
    wallet_id         UUID                           NOT NULL,
    provider          VARCHAR(20)                    NOT NULL,
    provider_reference VARCHAR(100),
    type              VARCHAR(20)                    NOT NULL,
    amount            DECIMAL(19, 4)                 NOT NULL,
    currency          VARCHAR(3)                     NOT NULL,
    status            VARCHAR(20)                    NOT NULL,
    idempotency_key   VARCHAR(100)                   NOT NULL,
    description       VARCHAR(255),
    created_at        TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    completed_at      TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT uc_payment_public_id UNIQUE (public_id),
    CONSTRAINT uc_payment_reference UNIQUE (reference),
    CONSTRAINT uc_payment_customer_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT ck_payment_provider CHECK (provider IN ('MPESA')),
    CONSTRAINT ck_payment_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT ck_payment_amount CHECK (amount > 0)
);

CREATE INDEX idx_payment_customer_created ON payment (customer_id, created_at);
CREATE INDEX idx_payment_status_created ON payment (status, created_at);
CREATE INDEX idx_payment_wallet ON payment (wallet_id);

CREATE TABLE payment_attempt
(
    id                UUID                           NOT NULL,
    payment_id        UUID                           NOT NULL,
    attempt_number    INTEGER                        NOT NULL,
    status            VARCHAR(20)                    NOT NULL,
    request_timestamp TIMESTAMP(6) WITHOUT TIME ZONE,
    response_timestamp TIMESTAMP(6) WITHOUT TIME ZONE,
    error_code        VARCHAR(100),
    error_message     TEXT,
    created_at        TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_payment_attempt PRIMARY KEY (id),
    CONSTRAINT uc_payment_attempt_number UNIQUE (payment_id, attempt_number),
    CONSTRAINT fk_payment_attempt_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT ck_payment_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_payment_attempt_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
);

CREATE INDEX idx_payment_attempt_status ON payment_attempt (status, created_at);

CREATE TABLE mpesa_transaction
(
    id                       UUID                           NOT NULL,
    payment_attempt_id       UUID                           NOT NULL,
    merchant_request_id      VARCHAR(100),
    checkout_request_id      VARCHAR(100),
    request_id               VARCHAR(100),
    conversation_id          VARCHAR(100),
    originator_conversation_id VARCHAR(100),
    mpesa_receipt_number     VARCHAR(50),
    result_code              INTEGER,
    result_description       VARCHAR(500),
    phone_number             VARCHAR(20),
    transaction_date         TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at               TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at               TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_mpesa_transaction PRIMARY KEY (id),
    CONSTRAINT uc_mpesa_transaction_attempt UNIQUE (payment_attempt_id),
    CONSTRAINT uc_mpesa_receipt_number UNIQUE (mpesa_receipt_number),
    CONSTRAINT fk_mpesa_transaction_attempt FOREIGN KEY (payment_attempt_id) REFERENCES payment_attempt (id)
);

CREATE TABLE payment_callback
(
    id                 UUID                           NOT NULL,
    payment_id         UUID,
    provider           VARCHAR(20)                    NOT NULL,
    callback_type      VARCHAR(50)                    NOT NULL,
    provider_reference VARCHAR(150),
    payload            JSONB                          NOT NULL,
    received_at        TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    processed_at       TIMESTAMP(6) WITHOUT TIME ZONE,
    processing_status  VARCHAR(20)                    NOT NULL,
    error_message      TEXT,
    CONSTRAINT pk_payment_callback PRIMARY KEY (id),
    CONSTRAINT fk_payment_callback_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT ck_payment_callback_provider CHECK (provider IN ('MPESA')),
    CONSTRAINT ck_payment_callback_processing_status CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'IGNORED'))
);

CREATE INDEX idx_payment_callback_payment_received ON payment_callback (payment_id, received_at);
CREATE INDEX idx_payment_callback_status_received ON payment_callback (processing_status, received_at);
CREATE INDEX idx_payment_callback_provider_reference ON payment_callback (provider, provider_reference);

CREATE TABLE outbox_event
(
    id             UUID                           NOT NULL,
    customer_id    UUID,
    aggregate_type VARCHAR(100)                   NOT NULL,
    aggregate_id   UUID                           NOT NULL,
    event_type     VARCHAR(150)                   NOT NULL,
    payload        JSONB                          NOT NULL,
    correlation_id UUID                           NOT NULL,
    status         VARCHAR(20)                    NOT NULL,
    attempt_count  INTEGER                        NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    published_at   TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_outbox_status_created ON outbox_event (status, created_at);
