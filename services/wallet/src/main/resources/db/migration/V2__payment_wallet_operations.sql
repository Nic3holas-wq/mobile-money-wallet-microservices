ALTER TABLE wallet
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN reserved_balance DECIMAL(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE wallet
    ADD CONSTRAINT ck_wallet_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'FROZEN', 'CLOSED')),
    ADD CONSTRAINT ck_wallet_reserved_balance CHECK (reserved_balance >= 0 AND reserved_balance <= balance);

CREATE TABLE wallet_payment_operation
(
    id               UUID                           NOT NULL,
    wallet_id        UUID                           NOT NULL,
    payment_reference VARCHAR(100)                   NOT NULL,
    kind             VARCHAR(20)                    NOT NULL,
    state            VARCHAR(20)                    NOT NULL,
    amount           DECIMAL(19, 4)                 NOT NULL,
    currency         VARCHAR(3)                     NOT NULL,
    created_at       TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at       TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    version          BIGINT                         NOT NULL DEFAULT 0,
    CONSTRAINT pk_wallet_payment_operation PRIMARY KEY (id),
    CONSTRAINT fk_wallet_payment_operation_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT uc_wallet_payment_operation_reference UNIQUE (wallet_id, payment_reference),
    CONSTRAINT ck_wallet_payment_operation_kind CHECK (kind IN ('RESERVATION', 'DEPOSIT')),
    CONSTRAINT ck_wallet_payment_operation_state CHECK (state IN ('RESERVED', 'COMMITTED', 'RELEASED', 'POSTED')),
    CONSTRAINT ck_wallet_payment_operation_amount CHECK (amount > 0)
);

CREATE INDEX idx_wallet_payment_operation_status
    ON wallet_payment_operation (state, created_at);

ALTER TABLE wallet_ledger_entry
    ALTER COLUMN transfer_id DROP NOT NULL,
    ALTER COLUMN entry_type TYPE VARCHAR(20),
    ADD COLUMN payment_operation_id UUID;

ALTER TABLE wallet_ledger_entry
    ADD CONSTRAINT fk_wallet_ledger_entry_payment_operation
        FOREIGN KEY (payment_operation_id) REFERENCES wallet_payment_operation (id),
    ADD CONSTRAINT ck_wallet_ledger_entry_origin
        CHECK ((transfer_id IS NOT NULL AND payment_operation_id IS NULL)
            OR (transfer_id IS NULL AND payment_operation_id IS NOT NULL));
