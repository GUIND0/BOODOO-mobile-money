ALTER TABLE wallets DROP CONSTRAINT wallets_status_check;
ALTER TABLE wallets ADD COLUMN ledger_account_id UUID;
ALTER TABLE wallets ADD COLUMN activated_at TIMESTAMPTZ;
ALTER TABLE wallets ADD UNIQUE(integrator_id,ledger_account_id);
ALTER TABLE wallets ADD CHECK((status='PENDING' AND ledger_account_id IS NULL AND activated_at IS NULL) OR (status='ACTIVE' AND ledger_account_id IS NOT NULL AND activated_at IS NOT NULL));
CREATE TABLE wallet_activations(integrator_id VARCHAR(64) NOT NULL,wallet_id UUID NOT NULL,idempotency_key VARCHAR(128) NOT NULL,actor_id VARCHAR(128) NOT NULL,snapshot JSONB,PRIMARY KEY(integrator_id,wallet_id),UNIQUE(integrator_id,idempotency_key),FOREIGN KEY(wallet_id,integrator_id) REFERENCES wallets(id,integrator_id));
CREATE FUNCTION protect_wallet_activation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Activation records cannot be deleted'; END IF;
 IF OLD.snapshot IS NOT NULL OR NEW.snapshot IS NULL OR NEW.integrator_id<>OLD.integrator_id OR NEW.wallet_id<>OLD.wallet_id OR NEW.idempotency_key<>OLD.idempotency_key OR NEW.actor_id<>OLD.actor_id THEN RAISE EXCEPTION 'Only completion of the original activation is allowed'; END IF;
 RETURN NEW;END $$;
CREATE TRIGGER activation_guard BEFORE UPDATE OR DELETE ON wallet_activations FOR EACH ROW EXECUTE FUNCTION protect_wallet_activation();
