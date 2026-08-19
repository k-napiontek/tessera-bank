-- V3 - what the API needs the database to remember, and where references come from.
--
-- Three additions, all driven by WP-08's REST contract:
--
--   1. account.opened_date. The contract's Account requires openedDate, and created_at is not it.
--      created_at is when the row was inserted; opened_date is when the bank opened the account.
--      They differ the moment an account is migrated in from the mainframe, which is the whole
--      premise of this estate, so conflating them would be wrong on the first real record.
--
--   2. journal_entry.reverses. JournalEntry has carried a reverses() since WP-06 and the schema had
--      nowhere to put it, so a reversal round-tripped through the database losing the link to the
--      entry it corrected. The contract's Transfer.reversesTransferRef makes that link visible, and
--      an unenforced correction chain is not an audit trail. WP-08 task 7 writes the column; nothing
--      can produce a non-null value before then, because there is no reversal use case yet.
--
--   3. Two sequences. References are TB/HL + CCYYMMDD + a ten-digit sequence, and a sequence is the
--      one allocator that stays correct under concurrency without taking a lock a transfer would
--      then wait on.

ALTER TABLE account
    ADD COLUMN opened_date date NOT NULL DEFAULT CURRENT_DATE;

ALTER TABLE journal_entry
    ADD COLUMN reverses varchar(34);

ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_reverses_fk
    FOREIGN KEY (reverses) REFERENCES journal_entry (reference);

-- An entry cannot reverse itself. Cheap to state, and the alternative is a correction chain with a
-- cycle in it that every reader has to defend against.
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_reverses_not_self
    CHECK (reverses IS NULL OR reverses <> reference);

-- A transfer is reversed at most once. The use case checks this too, but a check in one code path is
-- not a control: two concurrent reversal requests both pass it, and the account ends up credited
-- twice for a single erroneous debit.
CREATE UNIQUE INDEX journal_entry_reverses_uq
    ON journal_entry (reverses)
    WHERE reverses IS NOT NULL;

-- Ten digits is the width the canonical data model fixes, so the sequence is capped rather than left
-- to overflow into an eleventh and produce a reference no consumer can parse. NO CYCLE because
-- wrapping would reissue a reference that already names a posted transfer.
CREATE SEQUENCE entry_reference_seq
    START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999 NO CYCLE;

CREATE SEQUENCE hold_reference_seq
    START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999 NO CYCLE;
