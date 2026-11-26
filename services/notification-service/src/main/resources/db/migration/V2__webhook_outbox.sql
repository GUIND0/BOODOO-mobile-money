CREATE TABLE webhook_outbox(integrator_id VARCHAR(64) NOT NULL,id UUID NOT NULL,payload TEXT NOT NULL,destination_version VARCHAR(64),state VARCHAR(16) NOT NULL CHECK(state IN ('UNCONFIGURED','READY','INFLIGHT','DELIVERED','DEAD')),attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,delivered_at TIMESTAMPTZ,last_code VARCHAR(64),lease_token UUID,lease_until TIMESTAMPTZ,PRIMARY KEY(integrator_id,id),FOREIGN KEY(integrator_id,id) REFERENCES notifications(integrator_id,id));
CREATE INDEX webhook_ready_idx ON webhook_outbox(state,next_attempt_at);
CREATE TABLE webhook_redrives(integrator_id VARCHAR(64) NOT NULL,id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,snapshot JSONB NOT NULL,PRIMARY KEY(integrator_id,id));
CREATE FUNCTION protect_webhook_payload() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR TG_TABLE_NAME='webhook_redrives' THEN RAISE EXCEPTION 'Webhook history is immutable';END IF;
 IF NEW.integrator_id<>OLD.integrator_id OR NEW.id<>OLD.id OR NEW.payload<>OLD.payload OR (OLD.state='DELIVERED' AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN RAISE EXCEPTION 'Webhook payload and delivered outcome are immutable';END IF;RETURN NEW;END $$;
CREATE TRIGGER webhook_payload_guard BEFORE UPDATE OR DELETE ON webhook_outbox FOR EACH ROW EXECUTE FUNCTION protect_webhook_payload();
CREATE TRIGGER webhook_redrive_guard BEFORE UPDATE OR DELETE ON webhook_redrives FOR EACH ROW EXECUTE FUNCTION protect_webhook_payload();
