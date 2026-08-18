-- The database's half of the ledger invariants.
--
-- The domain enforces all of these already. They are here too because the domain can be bypassed by
-- anything holding a connection string - a migration, a support script, a future service written by
-- someone who has not read WP-06 - and a database that trusts its callers has no invariants at all.
-- Neither layer alone is load-bearing.

-- ---------------------------------------------------------------------------------------------
-- Amounts are strictly positive. Direction carries the sign, exactly as MOVEREC does at stratum 0.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE posting
    ADD CONSTRAINT posting_amount_positive CHECK (amount_minor > 0);

ALTER TABLE hold
    ADD CONSTRAINT hold_amount_positive CHECK (amount_minor > 0);

-- ---------------------------------------------------------------------------------------------
-- Enumerations. The domain's own enums are the authority; these keep a hand-written UPDATE from
-- inventing a sixth account type that no Java code can map.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE posting
    ADD CONSTRAINT posting_direction_known CHECK (direction IN ('DEBIT', 'CREDIT'));

ALTER TABLE account
    ADD CONSTRAINT account_type_known
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'));

ALTER TABLE account
    ADD CONSTRAINT account_status_known CHECK (status IN ('OPEN', 'BLOCKED', 'CLOSED'));

ALTER TABLE hold
    ADD CONSTRAINT hold_status_known
        CHECK (status IN ('PLACED', 'CAPTURED', 'RELEASED', 'EXPIRED'));

-- ---------------------------------------------------------------------------------------------
-- Currency agreement, as two composite foreign keys rather than two triggers.
--
-- A posting must be in its entry's currency, because JournalEntry rejects a mixed-currency entry
-- outright, and in its account's currency, because no conversion exists anywhere in this estate.
-- Declarative beats procedural here: a foreign key cannot be forgotten on a new code path, and it
-- costs two unique indexes that are useful in their own right.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_currency_uq UNIQUE (reference, currency);

ALTER TABLE account
    ADD CONSTRAINT account_currency_uq UNIQUE (reference, currency);

ALTER TABLE posting
    ADD CONSTRAINT posting_entry_currency_fk
        FOREIGN KEY (entry_ref, currency) REFERENCES journal_entry (reference, currency);

ALTER TABLE posting
    ADD CONSTRAINT posting_account_currency_fk
        FOREIGN KEY (account_ref, currency) REFERENCES account (reference, currency);

ALTER TABLE balance
    ADD CONSTRAINT balance_account_currency_fk
        FOREIGN KEY (account_ref, currency) REFERENCES account (reference, currency);

ALTER TABLE hold
    ADD CONSTRAINT hold_account_currency_fk
        FOREIGN KEY (account_ref, currency) REFERENCES account (reference, currency);

-- ---------------------------------------------------------------------------------------------
-- A captured hold names the entry that captured it, and nothing else does.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hold
    ADD CONSTRAINT hold_captured_by_consistent
        CHECK ((status = 'CAPTURED') = (captured_by IS NOT NULL));

-- ---------------------------------------------------------------------------------------------
-- Double entry, enforced at commit.
--
-- Total debits must equal total credits for every entry. This cannot be a row-level CHECK: when the
-- first leg is inserted the others do not exist yet, so any per-row rule would reject every entry
-- ever written. A DEFERRABLE INITIALLY DEFERRED constraint trigger is the mechanism - it fires once
-- the transaction has written all the legs, immediately before commit.
--
-- An entry with no postings at all cannot be caught here: the trigger fires on posting rows, and
-- there are none. That case is the domain's - JournalEntry rejects an empty entry - and a headless
-- journal_entry row with no legs moves no money.
-- ---------------------------------------------------------------------------------------------
-- The table is addressed through TG_TABLE_SCHEMA rather than by an unqualified name. A function body
-- resolves unqualified names against the *caller's* search_path, not the schema it was created in, so
-- an unqualified 'posting' here works only by luck - and fails the moment the connection's search_path
-- differs from the migration's, which is exactly what happened the first time this was written.
CREATE FUNCTION assert_entry_balances() RETURNS trigger AS $$
DECLARE
    debits  bigint;
    credits bigint;
BEGIN
    EXECUTE format(
        'SELECT coalesce(sum(amount_minor) FILTER (WHERE direction = ''DEBIT''), 0),'
        '       coalesce(sum(amount_minor) FILTER (WHERE direction = ''CREDIT''), 0)'
        '  FROM %I.posting WHERE entry_ref = $1',
        TG_TABLE_SCHEMA)
    INTO debits, credits
    USING COALESCE(NEW.entry_ref, OLD.entry_ref);

    IF debits <> credits THEN
        RAISE EXCEPTION
            'journal entry % does not balance: debits %, credits %',
            COALESCE(NEW.entry_ref, OLD.entry_ref), debits, credits
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER posting_entry_balances
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_entry_balances();

-- ---------------------------------------------------------------------------------------------
-- Postings are append-only, in the database and not only in the port.
--
-- JournalEntryRepository offers append and no update or delete, so the Java side cannot express a
-- mutation. This is for everything that is not the Java side. A correction is a reversing entry -
-- that is what double-entry bookkeeping is for, and it is why the audit trail in WP-09 can be a chain
-- rather than a diff.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION reject_posting_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'posting is append-only: % rejected. Correct a posting with a reversing entry.',
        TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER posting_no_update
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW
    EXECUTE FUNCTION reject_posting_mutation();
