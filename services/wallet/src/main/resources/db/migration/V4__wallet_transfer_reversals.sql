CREATE TABLE wallet_transfer_reversal
(
    id                  UUID                           NOT NULL,
    original_transfer_id UUID                          NOT NULL,
    reversal_transfer_id UUID                          NOT NULL,
    admin_subject       VARCHAR(100)                   NOT NULL,
    reason              VARCHAR(255)                   NOT NULL,
    created_at          TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_wallet_transfer_reversal PRIMARY KEY (id),
    CONSTRAINT uq_transfer_reversal_original UNIQUE (original_transfer_id),
    CONSTRAINT uq_transfer_reversal_reversal UNIQUE (reversal_transfer_id),
    CONSTRAINT fk_transfer_reversal_original FOREIGN KEY (original_transfer_id) REFERENCES wallet_transfer (id),
    CONSTRAINT fk_transfer_reversal_reversal FOREIGN KEY (reversal_transfer_id) REFERENCES wallet_transfer (id),
    CONSTRAINT ck_transfer_reversal_distinct CHECK (original_transfer_id <> reversal_transfer_id)
);
