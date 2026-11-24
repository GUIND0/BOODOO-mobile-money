CREATE TABLE journal_reversals (
 id UUID PRIMARY KEY,integrator_id VARCHAR(64) NOT NULL,original_journal_id UUID NOT NULL,
 reason_code VARCHAR(64) NOT NULL CHECK(reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
 requested_by VARCHAR(128) NOT NULL,requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 approved_by VARCHAR(128),approved_at TIMESTAMPTZ,compensating_journal_id UUID,
 UNIQUE(integrator_id,original_journal_id),UNIQUE(integrator_id,compensating_journal_id),
 FOREIGN KEY(original_journal_id,integrator_id) REFERENCES journals(id,integrator_id),
 FOREIGN KEY(compensating_journal_id,integrator_id) REFERENCES journals(id,integrator_id),
 CHECK((approved_by IS NULL AND approved_at IS NULL AND compensating_journal_id IS NULL) OR (approved_by IS NOT NULL AND approved_by<>requested_by AND approved_at IS NOT NULL AND compensating_journal_id IS NOT NULL))
);
CREATE FUNCTION protect_reversal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Reversal history cannot be deleted';END IF;
 IF OLD.compensating_journal_id IS NOT NULL OR NEW.compensating_journal_id IS NULL OR
 (to_jsonb(NEW)-ARRAY['approved_by','approved_at','compensating_journal_id']) IS DISTINCT FROM
 (to_jsonb(OLD)-ARRAY['approved_by','approved_at','compensating_journal_id']) THEN RAISE EXCEPTION 'Only first independent approval is allowed';END IF;RETURN NEW;END $$;
CREATE TRIGGER reversal_guard BEFORE UPDATE OR DELETE ON journal_reversals FOR EACH ROW EXECUTE FUNCTION protect_reversal();
