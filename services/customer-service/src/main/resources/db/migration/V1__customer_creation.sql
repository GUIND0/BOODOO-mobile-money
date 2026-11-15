CREATE TABLE customers (
    id UUID PRIMARY KEY,
    integrator_id VARCHAR(64) NOT NULL,
    external_reference VARCHAR(128) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(16) NOT NULL,
    kyc_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (kyc_status = 'PENDING'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT customer_integrator_id_format CHECK (integrator_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'),
    CONSTRAINT customer_phone_format CHECK (phone_number ~ '^\+[1-9][0-9]{1,14}$'),
    CONSTRAINT customer_nonempty_profile CHECK (
        length(btrim(external_reference)) > 0 AND length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0
    ),
    CONSTRAINT customer_external_reference_unique UNIQUE (integrator_id, external_reference),
    CONSTRAINT customer_phone_unique UNIQUE (integrator_id, phone_number),
    CONSTRAINT customer_id_integrator_unique UNIQUE (id, integrator_id)
);

CREATE TABLE customer_creation_requests (
    integrator_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    creation_response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integrator_id, idempotency_key),
    CONSTRAINT creation_customer_integrator_fk FOREIGN KEY (customer_id, integrator_id)
        REFERENCES customers (id, integrator_id),
    CONSTRAINT creation_fingerprint_format CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT creation_response_object CHECK (jsonb_typeof(creation_response) = 'object')
);
