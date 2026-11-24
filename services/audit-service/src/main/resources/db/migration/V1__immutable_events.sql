CREATE TABLE audit_events(integrator_id VARCHAR(64) NOT NULL,id UUID NOT NULL,resource_id UUID NOT NULL,event_type VARCHAR(64) NOT NULL,reason_code VARCHAR(64) NOT NULL CHECK(reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),producer VARCHAR(128) NOT NULL,fingerprint CHAR(64) NOT NULL,recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,snapshot JSONB NOT NULL,PRIMARY KEY(integrator_id,id));
CREATE INDEX audit_resource_page_idx ON audit_events(integrator_id,resource_id,id);
CREATE FUNCTION immutable_audit_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Audit events are immutable'; END $$;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION immutable_audit_event();
