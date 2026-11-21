CREATE TABLE kyc_decisions (
    id UUID PRIMARY KEY, integrator_id VARCHAR(64) NOT NULL, customer_id UUID NOT NULL, submission_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('APPROVED','REJECTED')),
    reason_code VARCHAR(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    reviewed_by VARCHAR(128) NOT NULL, reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (integrator_id,submission_id), UNIQUE (id,integrator_id,customer_id),
    FOREIGN KEY (submission_id,integrator_id,customer_id) REFERENCES kyc_submissions(id,integrator_id,customer_id)
);
CREATE TABLE kyc_decision_requests (
    integrator_id VARCHAR(64) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,customer_id UUID NOT NULL,
    decision_id UUID NOT NULL,request_fingerprint CHAR(64) NOT NULL,
    response_snapshot JSONB NOT NULL CHECK (jsonb_typeof(response_snapshot)='object'),
    PRIMARY KEY (integrator_id,idempotency_key),
    FOREIGN KEY (decision_id,integrator_id,customer_id) REFERENCES kyc_decisions(id,integrator_id,customer_id)
);
CREATE TRIGGER kyc_decision_immutable BEFORE UPDATE OR DELETE ON kyc_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_customer_history_mutation();
CREATE TRIGGER kyc_decision_request_immutable BEFORE UPDATE OR DELETE ON kyc_decision_requests
    FOR EACH ROW EXECUTE FUNCTION reject_customer_history_mutation();
