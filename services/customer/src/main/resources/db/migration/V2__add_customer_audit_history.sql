CREATE TABLE customer_audit_record (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customer(id),
    actor_id UUID NOT NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    before_state JSONB NOT NULL,
    after_state JSONB NOT NULL
);
CREATE INDEX idx_customer_audit_history ON customer_audit_record(customer_id, occurred_at, id);

-- History survives deletion of a document or limit: target_id intentionally has no FK.
-- Application DML cannot alter historical evidence. Database owners can still change DDL.
CREATE FUNCTION reject_customer_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Customer audit history is append-only';
END;
$$;
CREATE TRIGGER customer_audit_no_mutation BEFORE UPDATE OR DELETE ON customer_audit_record
    FOR EACH ROW EXECUTE FUNCTION reject_customer_audit_mutation();
CREATE TRIGGER customer_audit_no_truncate BEFORE TRUNCATE ON customer_audit_record
    FOR EACH STATEMENT EXECUTE FUNCTION reject_customer_audit_mutation();
