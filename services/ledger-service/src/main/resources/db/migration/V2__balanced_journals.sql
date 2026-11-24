CREATE TABLE journals (
 id UUID PRIMARY KEY, integrator_id VARCHAR(64) NOT NULL, reference UUID NOT NULL,
 created_by VARCHAR(128) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(id,integrator_id), UNIQUE(integrator_id,reference)
);
CREATE TABLE journal_entries (
 integrator_id VARCHAR(64) NOT NULL, journal_id UUID NOT NULL, account_id UUID NOT NULL,
 side VARCHAR(6) NOT NULL CHECK(side IN ('DEBIT','CREDIT')), amount BIGINT NOT NULL CHECK(amount>0),
 PRIMARY KEY(journal_id,account_id), FOREIGN KEY(journal_id,integrator_id) REFERENCES journals(id,integrator_id),
 FOREIGN KEY(account_id,integrator_id) REFERENCES ledger_accounts(id,integrator_id)
);
CREATE INDEX journal_entries_account_idx ON journal_entries(integrator_id,account_id);
CREATE TABLE journal_requests (
 integrator_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
 fingerprint CHAR(64) NOT NULL, journal_id UUID NOT NULL, snapshot JSONB NOT NULL,
 PRIMARY KEY(integrator_id,idempotency_key), FOREIGN KEY(journal_id,integrator_id) REFERENCES journals(id,integrator_id)
);
CREATE FUNCTION check_balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; total NUMERIC; entries_count INTEGER; invalid BOOLEAN;
BEGIN
 IF TG_TABLE_NAME='journals' THEN target=NEW.id; ELSE target=NEW.journal_id; END IF;
 SELECT count(*),sum(CASE WHEN side='DEBIT' THEN amount::numeric ELSE -amount::numeric END)
 INTO entries_count,total FROM journal_entries WHERE journal_id=target;
 IF entries_count<2 OR total<>0 THEN RAISE EXCEPTION 'Journal must contain balanced entries'; END IF;
 PERFORM a.id FROM ledger_accounts a JOIN journal_entries e ON e.account_id=a.id AND e.integrator_id=a.integrator_id
 WHERE e.journal_id=target ORDER BY a.id FOR UPDATE OF a;
 SELECT EXISTS(SELECT 1 FROM ledger_accounts a JOIN journal_entries changed ON changed.account_id=a.id AND changed.journal_id=target
 JOIN journal_entries all_entries ON all_entries.account_id=a.id AND all_entries.integrator_id=a.integrator_id
 GROUP BY a.id,a.normal_balance HAVING sum(CASE WHEN all_entries.side=a.normal_balance THEN all_entries.amount::numeric ELSE -all_entries.amount::numeric END) NOT BETWEEN 0 AND 9223372036854775807) INTO invalid;
 IF invalid THEN RAISE EXCEPTION 'Account normal balance outside supported range'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balanced_journal AFTER INSERT ON journals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_balanced_journal();
CREATE CONSTRAINT TRIGGER balanced_entry AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_balanced_journal();
CREATE TRIGGER journal_immutable BEFORE UPDATE OR DELETE ON journals FOR EACH ROW EXECUTE FUNCTION reject_account_mutation();
CREATE TRIGGER entry_immutable BEFORE UPDATE OR DELETE ON journal_entries FOR EACH ROW EXECUTE FUNCTION reject_account_mutation();
CREATE TRIGGER journal_request_immutable BEFORE UPDATE OR DELETE ON journal_requests FOR EACH ROW EXECUTE FUNCTION reject_account_mutation();
