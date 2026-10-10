ALTER TABLE payment_callback
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP(6) WITHOUT TIME ZONE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_payment_callback_retry_count'
          AND conrelid = 'payment_callback'::regclass
    ) THEN
        ALTER TABLE payment_callback
            ADD CONSTRAINT ck_payment_callback_retry_count CHECK (retry_count >= 0);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_payment_callback_retry
    ON payment_callback (processing_status, next_retry_at)
    WHERE processing_status = 'FAILED';
