-- V4 - remittance information on an entry.
--
-- The contract carries an optional `reference` on Transfer and on every Movement: 35 characters,
-- the width of the SEPA field, copied onto both legs. Nothing in WP-06 or WP-07 stores it, so a
-- client that sent one would have it silently dropped and a statement could never show what a
-- payment was for.
--
-- It lives on journal_entry rather than on posting because the canonical data model says the
-- transfer's reference is copied onto both movements - one value, not two that could disagree - and
-- because no invariant depends on it. That is also why JournalEntry does not carry it: remittance
-- information is an attachment to an entry, not accounting data, and putting it on the aggregate
-- would mean the double-entry invariants had a free-text field inside them.

ALTER TABLE journal_entry
    ADD COLUMN reference_text varchar(35);

-- The statement reads postings for one account ordered by (value_date, created_at, entry_ref, seq).
-- posting already carries posting_account_ix on account_ref; the sort keys live on journal_entry, so
-- the ordering is done after the join. See follow-up F-24: denormalising value_date and created_at
-- onto posting would make the keyset seek index-only, and that is a change to WP-07's schema shape
-- rather than an addition to it.
CREATE INDEX journal_entry_statement_ix
    ON journal_entry (value_date, created_at, reference);
