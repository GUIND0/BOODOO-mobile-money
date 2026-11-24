CREATE TABLE reconciliation_reports(id UUID PRIMARY KEY,integrator_id VARCHAR(64) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,actor_id VARCHAR(128) NOT NULL,snapshot JSONB NOT NULL,UNIQUE(integrator_id,idempotency_key));
CREATE FUNCTION immutable_reconciliation_report() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Reports are immutable'; END $$;
CREATE TRIGGER report_immutable BEFORE UPDATE OR DELETE ON reconciliation_reports FOR EACH ROW EXECUTE FUNCTION immutable_reconciliation_report();
