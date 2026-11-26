CREATE TABLE journal_cancellations (
 integrator_id VARCHAR(64) NOT NULL, reference UUID NOT NULL,
 cancelled_by VARCHAR(128) NOT NULL, cancelled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(integrator_id,reference)
);
CREATE TRIGGER cancellation_immutable BEFORE UPDATE OR DELETE ON journal_cancellations
 FOR EACH ROW EXECUTE FUNCTION reject_account_mutation();
CREATE FUNCTION reject_cancelled_journal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('journal-reference:'||NEW.integrator_id||':'||NEW.reference::text,0));
 IF EXISTS(SELECT 1 FROM journal_cancellations WHERE integrator_id=NEW.integrator_id AND reference=NEW.reference) THEN
 RAISE EXCEPTION 'Journal reference is cancelled'; END IF; RETURN NEW; END $$;
CREATE TRIGGER cancellation_barrier BEFORE INSERT ON journals FOR EACH ROW EXECUTE FUNCTION reject_cancelled_journal();
