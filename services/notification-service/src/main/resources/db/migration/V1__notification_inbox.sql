CREATE TABLE notifications(integrator_id VARCHAR(64) NOT NULL,id UUID NOT NULL,customer_id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,producer VARCHAR(128) NOT NULL,snapshot JSONB NOT NULL,acknowledged_at TIMESTAMPTZ,acknowledged_by VARCHAR(128),PRIMARY KEY(integrator_id,id));
CREATE INDEX notification_customer_page_idx ON notifications(integrator_id,customer_id,id);
CREATE FUNCTION protect_notification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Notifications cannot be deleted'; END IF;
 IF OLD.acknowledged_at IS NOT NULL OR NEW.acknowledged_at IS NULL OR NEW.acknowledged_by IS NULL OR (to_jsonb(NEW)-'acknowledged_at'-'acknowledged_by') IS DISTINCT FROM (to_jsonb(OLD)-'acknowledged_at'-'acknowledged_by') THEN RAISE EXCEPTION 'Only first acknowledgement is allowed'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER notification_guard BEFORE UPDATE OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION protect_notification();
