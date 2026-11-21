ALTER TABLE customers ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0 CHECK (profile_version >= 0);
ALTER TABLE customers ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE customers ADD COLUMN date_of_birth DATE;
ALTER TABLE customers ADD COLUMN nationality VARCHAR(2);
ALTER TABLE customers ADD COLUMN residence_country VARCHAR(2);
ALTER TABLE customers ADD COLUMN address_line VARCHAR(200);
ALTER TABLE customers ADD COLUMN city VARCHAR(100);
CREATE TABLE customer_profile_updates (
    integrator_id VARCHAR(64) NOT NULL, customer_id UUID NOT NULL, profile_version BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL, changed_fields JSONB NOT NULL CHECK (jsonb_typeof(changed_fields) = 'array'),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integrator_id, customer_id, profile_version),
    FOREIGN KEY (customer_id, integrator_id) REFERENCES customers (id, integrator_id)
);
CREATE FUNCTION reject_customer_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Customer history records are immutable'; END $$;
CREATE TRIGGER profile_history_immutable BEFORE UPDATE OR DELETE ON customer_profile_updates
    FOR EACH ROW EXECUTE FUNCTION reject_customer_history_mutation();
