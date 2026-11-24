CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    integrator_id VARCHAR(64) NOT NULL CHECK (integrator_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'),
    code VARCHAR(64) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_-]{0,63}$'),
    currency VARCHAR(3) NOT NULL CHECK (currency='XOF'),
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance VARCHAR(6) NOT NULL,
    created_by VARCHAR(128) NOT NULL CHECK (length(btrim(created_by))>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (integrator_id,code,currency), UNIQUE (id,integrator_id),
    CHECK ((account_type IN ('ASSET','EXPENSE') AND normal_balance='DEBIT')
        OR (account_type IN ('LIABILITY','EQUITY','INCOME') AND normal_balance='CREDIT'))
);
CREATE TABLE account_creation_requests (
    integrator_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    account_id UUID NOT NULL,
    response_snapshot JSONB NOT NULL CHECK (jsonb_typeof(response_snapshot)='object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (integrator_id,idempotency_key),
    FOREIGN KEY (account_id,integrator_id) REFERENCES ledger_accounts(id,integrator_id)
);
CREATE FUNCTION reject_account_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Accounting account metadata and creation snapshots are immutable'; END $$;
CREATE TRIGGER account_immutable BEFORE UPDATE OR DELETE ON ledger_accounts FOR EACH ROW EXECUTE FUNCTION reject_account_mutation();
CREATE TRIGGER account_request_immutable BEFORE UPDATE OR DELETE ON account_creation_requests FOR EACH ROW EXECUTE FUNCTION reject_account_mutation();
