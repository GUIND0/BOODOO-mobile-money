CREATE TABLE merchants(integrator_id VARCHAR(64) NOT NULL,id UUID NOT NULL,wallet_id UUID NOT NULL,code VARCHAR(64) NOT NULL,state VARCHAR(12) NOT NULL CHECK(state IN ('ACTIVE','SUSPENDED')),version BIGINT NOT NULL CHECK(version>0),fingerprint CHAR(64) NOT NULL,PRIMARY KEY(integrator_id,id),UNIQUE(integrator_id,wallet_id),UNIQUE(integrator_id,code));
CREATE TABLE merchant_events(integrator_id VARCHAR(64) NOT NULL,id UUID NOT NULL,merchant_id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,snapshot JSONB NOT NULL,PRIMARY KEY(integrator_id,id),FOREIGN KEY(integrator_id,merchant_id) REFERENCES merchants(integrator_id,id));
CREATE FUNCTION protect_merchant_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Merchant history is immutable';END IF;
 IF TG_TABLE_NAME='merchant_events' OR (to_jsonb(NEW)-ARRAY['state','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version']) OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Merchant identity is immutable';END IF;RETURN NEW;END $$;
CREATE TRIGGER merchant_guard BEFORE UPDATE OR DELETE ON merchants FOR EACH ROW EXECUTE FUNCTION protect_merchant_identity();
CREATE TRIGGER merchant_event_guard BEFORE UPDATE OR DELETE ON merchant_events FOR EACH ROW EXECUTE FUNCTION protect_merchant_identity();
