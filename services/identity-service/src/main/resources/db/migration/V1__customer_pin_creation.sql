CREATE TABLE customer_pins (
    integrator_id VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    pin_hash VARCHAR(255) NOT NULL CHECK (pin_hash LIKE '$argon2id$%'),
    pepper_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integrator_id, customer_id),
    CHECK (integrator_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$')
);

CREATE TABLE pin_creation_requests (
    integrator_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    customer_id UUID NOT NULL,
    request_hmac CHAR(64) NOT NULL CHECK (request_hmac ~ '^[0-9a-f]{64}$'),
    pepper_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integrator_id, idempotency_key),
    FOREIGN KEY (integrator_id, customer_id) REFERENCES customer_pins (integrator_id, customer_id)
);

CREATE TABLE pin_setup_attempts (
    integrator_id VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 10),
    PRIMARY KEY (integrator_id, customer_id)
);

CREATE FUNCTION reject_pin_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'PIN creation records are immutable';
END $$;

CREATE TRIGGER pin_creation_immutable BEFORE UPDATE OR DELETE ON customer_pins
    FOR EACH ROW EXECUTE FUNCTION reject_pin_mutation();
CREATE TRIGGER pin_request_immutable BEFORE UPDATE OR DELETE ON pin_creation_requests
    FOR EACH ROW EXECUTE FUNCTION reject_pin_mutation();
