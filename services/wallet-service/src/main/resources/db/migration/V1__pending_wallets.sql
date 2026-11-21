CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    integrator_id VARCHAR(64) NOT NULL CHECK (integrator_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'),
    customer_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency = 'XOF'),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status = 'PENDING'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (integrator_id, customer_id, currency),
    UNIQUE (id, integrator_id)
);
CREATE TABLE wallet_creation_requests (
    integrator_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    wallet_id UUID NOT NULL,
    response_snapshot JSONB NOT NULL CHECK (jsonb_typeof(response_snapshot) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integrator_id, idempotency_key),
    FOREIGN KEY (wallet_id, integrator_id) REFERENCES wallets(id, integrator_id)
);
CREATE FUNCTION reject_wallet_request_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Wallet creation snapshots are immutable'; END $$;
CREATE TRIGGER wallet_request_immutable BEFORE UPDATE OR DELETE ON wallet_creation_requests
    FOR EACH ROW EXECUTE FUNCTION reject_wallet_request_mutation();
