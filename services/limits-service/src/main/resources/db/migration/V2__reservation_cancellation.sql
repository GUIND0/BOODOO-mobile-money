CREATE TABLE limit_cancellations(integrator_id VARCHAR(64) NOT NULL,reference UUID NOT NULL,actor_id VARCHAR(128) NOT NULL,PRIMARY KEY(integrator_id,reference));
CREATE FUNCTION reject_limit_cancellation_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Cancellation tombstones are immutable';END $$;
CREATE TRIGGER limit_cancellation_guard BEFORE UPDATE OR DELETE ON limit_cancellations FOR EACH ROW EXECUTE FUNCTION reject_limit_cancellation_mutation();
