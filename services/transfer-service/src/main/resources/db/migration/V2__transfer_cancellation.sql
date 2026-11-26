ALTER TABLE transfers DROP CONSTRAINT transfers_state_check;
ALTER TABLE transfers ADD CHECK(state IN ('CREATED','AUTHORIZED','RESERVING','RESERVED','POSTING','POSTED','COMPLETED','REJECTED','CANCELLING','CANCELLED'));
CREATE OR REPLACE FUNCTION protect_transfer_intent() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Transfer history cannot be deleted';END IF;
 IF (to_jsonb(NEW)-ARRAY['state','context','journal_id','last_code','lease_token','lease_until','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','context','journal_id','last_code','lease_token','lease_until','updated_at']) THEN RAISE EXCEPTION 'Financial intent is immutable';END IF;
 IF NEW.state<>OLD.state AND NOT ((OLD.state NOT IN ('POSTED','COMPLETED','REJECTED','CANCELLED') AND NEW.state='CANCELLING') OR (OLD.state='CANCELLING' AND NEW.state IN ('CANCELLED','POSTED')) OR (OLD.state='CREATED' AND NEW.state IN ('AUTHORIZED','REJECTED')) OR (OLD.state='AUTHORIZED' AND NEW.state IN ('RESERVING','REJECTED')) OR (OLD.state='RESERVING' AND NEW.state='RESERVED') OR (OLD.state='RESERVED' AND NEW.state='POSTING') OR (OLD.state='POSTING' AND NEW.state='POSTED') OR (OLD.state='POSTED' AND NEW.state='COMPLETED')) THEN RAISE EXCEPTION 'Invalid transfer transition';END IF;
 RETURN NEW;END $$;
