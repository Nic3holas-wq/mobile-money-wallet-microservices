
CREATE TABLE wallet
(
    id            UUID                                         NOT NULL,
    public_id     UUID                                         NOT NULL,
    customer_id   UUID                                         NOT NULL,
    wallet_number VARCHAR(20)                                  NOT NULL,
    currency      VARCHAR(3)                     DEFAULT 'KES' NOT NULL,
    balance       DECIMAL(19, 4)                 DEFAULT 0     NOT NULL,
    version       BIGINT                         DEFAULT 0     NOT NULL,
    created_at    TIMESTAMP(6) WITHOUT TIME ZONE               NOT NULL,
    updated_at    TIMESTAMP(6) WITHOUT TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT pk_wallet PRIMARY KEY (id),
    CONSTRAINT uc_wallet_wallet_number UNIQUE (wallet_number),
    CONSTRAINT ck_wallet_balance CHECK (balance >= 0)
);

CREATE UNIQUE INDEX idx_wallet_customer_id ON wallet (customer_id);
CREATE UNIQUE INDEX idx_wallet_public_id ON wallet (public_id);

-- ---------- wallet_transfer ----------
CREATE TABLE wallet_transfer
(
    id                    UUID                           NOT NULL,
    reference             VARCHAR(50)                    NOT NULL,
    source_wallet_id      UUID                           NOT NULL,
    destination_wallet_id UUID                           NOT NULL,
    amount                DECIMAL(19, 4)                 NOT NULL,
    currency              VARCHAR(3)                     NOT NULL,
    status                VARCHAR(20)                    NOT NULL,
    idempotency_key       VARCHAR(100)                   NOT NULL,
    description           VARCHAR(255),
    failure_reason        VARCHAR(255),
    created_at            TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    completed_at          TIMESTAMP(6) WITHOUT TIME ZONE,
    version               BIGINT                         NOT NULL,
    CONSTRAINT pk_wallet_transfer PRIMARY KEY (id),
    CONSTRAINT uc_wallet_transfer_reference UNIQUE (reference),
    CONSTRAINT uc_transfer_wallet_idem UNIQUE (source_wallet_id, idempotency_key),
    CONSTRAINT fk_wallet_transfer_on_source_wallet
        FOREIGN KEY (source_wallet_id) REFERENCES wallet (id),
    CONSTRAINT fk_wallet_transfer_on_destination_wallet
        FOREIGN KEY (destination_wallet_id) REFERENCES wallet (id),
    CONSTRAINT ck_transfer_amount CHECK (amount > 0),
    CONSTRAINT ck_transfer_distinct CHECK (source_wallet_id <> destination_wallet_id),
    CONSTRAINT ck_transfer_status
        CHECK (status IN ('PENDING_STEPUP', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transfer_source_wallet ON wallet_transfer (source_wallet_id, created_at);
CREATE INDEX idx_transfer_destination_wallet ON wallet_transfer (destination_wallet_id, created_at);

-- ---------- wallet_ledger_entry ----------
CREATE TABLE wallet_ledger_entry
(
    id             UUID                           NOT NULL,
    wallet_id      UUID                           NOT NULL,
    transfer_id    UUID                           NOT NULL,
    entry_type     VARCHAR(10)                    NOT NULL,
    direction      VARCHAR(6)                     NOT NULL,
    amount         DECIMAL(19, 4)                 NOT NULL,
    balance_before DECIMAL(19, 4)                 NOT NULL,
    balance_after  DECIMAL(19, 4)                 NOT NULL,
    currency       VARCHAR(3)                     NOT NULL,
    description    VARCHAR(255),
    created_at     TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_wallet_ledger_entry PRIMARY KEY (id),
    CONSTRAINT uc_ledger_transfer_wallet_direction UNIQUE (transfer_id, wallet_id, direction),
    CONSTRAINT fk_wallet_ledger_entry_on_wallet
        FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT fk_wallet_ledger_entry_on_transfer
        FOREIGN KEY (transfer_id) REFERENCES wallet_transfer (id),
    CONSTRAINT ck_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_balance CHECK (
        (direction = 'DEBIT'  AND balance_after = balance_before - amount) OR
        (direction = 'CREDIT' AND balance_after = balance_before + amount))
);

CREATE INDEX idx_ledger_wallet_created ON wallet_ledger_entry (wallet_id, created_at);
CREATE INDEX idx_ledger_transfer_id ON wallet_ledger_entry (transfer_id);

-- Ledger rows are append-only: block UPDATE and DELETE
CREATE FUNCTION prevent_ledger_modification() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'wallet_ledger_entry is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_immutable
    BEFORE UPDATE OR DELETE
    ON wallet_ledger_entry
    FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_modification();

-- ---------- stepup_token ----------
CREATE TABLE stepup_token
(
    id           UUID                           NOT NULL,
    transfer_id  UUID                           NOT NULL,
    wallet_id    UUID                           NOT NULL,
    token_hash   VARCHAR(255)                   NOT NULL,
    channel      VARCHAR(20)                    NOT NULL,
    status       VARCHAR(20)                    NOT NULL,
    attempts     INTEGER                        NOT NULL DEFAULT 0,
    resend_count INTEGER                        NOT NULL DEFAULT 0,
    expires_at   TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    verified_at  TIMESTAMP(6) WITHOUT TIME ZONE,
    consumed_at  TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at   TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_stepup_token PRIMARY KEY (id),
    CONSTRAINT fk_stepup_token_on_transfer
        FOREIGN KEY (transfer_id) REFERENCES wallet_transfer (id),
    CONSTRAINT fk_stepup_token_on_wallet
        FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT ck_stepup_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'CONSUMED', 'EXPIRED', 'FAILED'))
);

CREATE INDEX idx_stepup_token_transfer_id ON stepup_token (transfer_id);
CREATE INDEX idx_stepup_token_wallet_id ON stepup_token (wallet_id);

-- Only one active (PENDING) OTP per transfer
CREATE UNIQUE INDEX uq_stepup_active
    ON stepup_token (transfer_id) WHERE status = 'PENDING';

-- ---------- outbox_event ----------
CREATE TABLE outbox_event
(
    id             UUID                           NOT NULL,
    customer_id    UUID,
    aggregate_type VARCHAR(255)                   NOT NULL,
    aggregate_id   UUID                           NOT NULL,
    event_type     VARCHAR(255)                   NOT NULL,
    payload        JSONB                          NOT NULL,
    correlation_id UUID                           NOT NULL,
    status         VARCHAR(255)                   NOT NULL,
    attempt_count  INTEGER                        NOT NULL,
    last_error     TEXT,
    created_at     TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    published_at   TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_status_created ON outbox_event (status, created_at);