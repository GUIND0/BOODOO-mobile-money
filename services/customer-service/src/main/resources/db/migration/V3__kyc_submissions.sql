ALTER TABLE customers DROP CONSTRAINT customers_kyc_status_check;
ALTER TABLE customers ADD CONSTRAINT customers_kyc_status_check CHECK (kyc_status IN ('PENDING','UNDER_REVIEW','APPROVED','REJECTED'));
CREATE TABLE kyc_submissions (
    id UUID PRIMARY KEY, integrator_id VARCHAR(64) NOT NULL, customer_id UUID NOT NULL,
    profile_version BIGINT NOT NULL, profile_snapshot JSONB NOT NULL CHECK (jsonb_typeof(profile_snapshot)='object'),
    documents JSONB NOT NULL CHECK (jsonb_typeof(documents)='array'), submitted_by VARCHAR(128) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id,integrator_id,customer_id), FOREIGN KEY (customer_id,integrator_id) REFERENCES customers(id,integrator_id)
);
ALTER TABLE customers ADD COLUMN active_kyc_submission_id UUID;
ALTER TABLE customers ADD FOREIGN KEY (active_kyc_submission_id,integrator_id,id) REFERENCES kyc_submissions(id,integrator_id,customer_id);
ALTER TABLE customers ADD CHECK ((kyc_status='PENDING' AND active_kyc_submission_id IS NULL)
    OR (kyc_status IN ('UNDER_REVIEW','APPROVED','REJECTED') AND active_kyc_submission_id IS NOT NULL));
CREATE TABLE kyc_submission_requests (
    integrator_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NOT NULL, customer_id UUID NOT NULL,
    submission_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL,
    response_snapshot JSONB NOT NULL CHECK (jsonb_typeof(response_snapshot)='object'),
    PRIMARY KEY (integrator_id,idempotency_key),
    FOREIGN KEY (submission_id,integrator_id,customer_id) REFERENCES kyc_submissions(id,integrator_id,customer_id)
);
CREATE TRIGGER kyc_submission_immutable BEFORE UPDATE OR DELETE ON kyc_submissions
    FOR EACH ROW EXECUTE FUNCTION reject_customer_history_mutation();
CREATE TRIGGER kyc_request_immutable BEFORE UPDATE OR DELETE ON kyc_submission_requests
    FOR EACH ROW EXECUTE FUNCTION reject_customer_history_mutation();
