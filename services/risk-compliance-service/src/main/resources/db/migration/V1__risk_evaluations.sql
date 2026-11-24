CREATE TABLE risk_evaluations(integrator_id VARCHAR(64) NOT NULL,id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,reference UUID NOT NULL,customer_id UUID NOT NULL,policy_version VARCHAR(64) NOT NULL,snapshot JSONB NOT NULL,PRIMARY KEY(integrator_id,id));
CREATE FUNCTION immutable_risk_evaluation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Risk evaluations are immutable'; END $$;
CREATE TRIGGER risk_immutable BEFORE UPDATE OR DELETE ON risk_evaluations FOR EACH ROW EXECUTE FUNCTION immutable_risk_evaluation();
