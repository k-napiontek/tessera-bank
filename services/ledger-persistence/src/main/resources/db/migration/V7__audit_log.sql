-- V7 - the audit trail: append-only in the schema, and hash-chained so that tampering is detectable
-- rather than merely discouraged.
--
-- REQ-AUD-001. Two mechanisms, doing different jobs:
--
--   * the trigger stops the application, and anything else holding an ordinary connection, from
--     changing a row that has been written. That is the control that operates every day.
--   * the hash chain catches whoever gets past the trigger - a superuser, a DBA with DDL rights, a
--     restored backup with a row quietly edited. It cannot prevent the edit. It makes the edit
--     impossible to hide, which is the honest goal: a bank cannot stop its own DBAs, it can only
--     ensure they cannot do it silently.
--
-- A control that claims to prevent what it can only detect is worse than one that states the
-- difference.

CREATE TABLE audit_record (
    -- Chain order. Assigned inside the advisory lock the adapter takes before reading the last hash,
    -- so sequence order and chain order are the same order rather than merely usually the same.
    seq             bigint      GENERATED ALWAYS AS IDENTITY,

    occurred_at     timestamptz NOT NULL,

    -- Who. Not a person: this tier has no customer identity and no authentication of its own. It is
    -- the calling system, which is what the ledger can honestly attest to.
    actor           varchar(64) NOT NULL,

    -- What. An enumeration on the Java side, stored as its name.
    action          varchar(48) NOT NULL,

    -- Which account, transfer or hold it was about.
    subject_ref     varchar(34) NOT NULL,

    -- The request it happened under, joining this row to every log line and every event from the
    -- same customer action. Null only for work with no inbound request, such as an expiry sweep.
    correlation_id  uuid        NULL,

    -- References, statuses, amounts in minor units, currency codes. Never personal data: an audit
    -- row is retained for years, which makes it the worst possible place to put any.
    before_state    jsonb       NOT NULL,
    after_state     jsonb       NOT NULL,

    previous_hash   char(64)    NOT NULL,
    hash            char(64)    NOT NULL,

    CONSTRAINT audit_record_pk PRIMARY KEY (seq),
    CONSTRAINT audit_record_hash_hex CHECK (hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_record_previous_hash_hex CHECK (previous_hash ~ '^[0-9a-f]{64}$'),

    -- No two rows may chain onto the same predecessor. Without this the chain is a tree: a row can
    -- be replaced by a second row claiming the same parent, and a verifier walking one branch sees
    -- an intact chain while the other branch is the one that actually happened.
    CONSTRAINT audit_record_previous_unique UNIQUE (previous_hash),
    CONSTRAINT audit_record_hash_unique UNIQUE (hash)
);

-- The verifier walks the whole table in order; the relay of an operator's question ("what happened
-- to this account?") reads by subject.
CREATE INDEX audit_record_subject_ix ON audit_record (subject_ref, seq);

-- ---------------------------------------------------------------------------------------------
-- Append-only, enforced.
--
-- Same shape as reject_posting_mutation in V2, and the same reason for TG_TABLE_SCHEMA: a function
-- body resolves unqualified names against the caller's search_path rather than the schema it was
-- created in. This function names no table at all, which sidesteps the trap entirely - it is
-- recorded here so the next function added to this file does not reintroduce it.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'audit_record is append-only: % rejected. An audit trail that can be edited is not one.',
        TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_record_no_change
    BEFORE UPDATE OR DELETE ON audit_record
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_mutation();

-- TRUNCATE fires no row trigger, so the rule above does not see it: one statement would empty the
-- entire trail and the control above would report nothing. A statement-level trigger is the only
-- thing that catches it, and an append-only table that can be emptied in one statement is not
-- append-only in any sense an auditor would accept.
CREATE TRIGGER audit_record_no_truncate
    BEFORE TRUNCATE ON audit_record
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_audit_mutation();
