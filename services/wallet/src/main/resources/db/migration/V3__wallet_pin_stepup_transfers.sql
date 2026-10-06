ALTER TABLE wallet_transfer
    ADD COLUMN expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes');

CREATE INDEX idx_wallet_transfer_status_expires
    ON wallet_transfer (status, expires_at);

ALTER TABLE stepup_token
    ADD COLUMN updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT NOW();

DROP INDEX uq_stepup_active;
CREATE UNIQUE INDEX uq_stepup_active
    ON stepup_token (transfer_id) WHERE status IN ('PENDING', 'VERIFIED');

CREATE TABLE wallet_pin_credential
(
    customer_id     UUID                           NOT NULL,
    pin_salt        VARCHAR(64)                    NOT NULL,
    pin_hash        VARCHAR(64)                    NOT NULL,
    failed_attempts INTEGER                        NOT NULL DEFAULT 0,
    locked_until    TIMESTAMP(6) WITHOUT TIME ZONE,
    updated_at      TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    version         BIGINT                         NOT NULL DEFAULT 0,
    CONSTRAINT pk_wallet_pin_credential PRIMARY KEY (customer_id),
    CONSTRAINT ck_wallet_pin_failed_attempts CHECK (failed_attempts >= 0)
);
