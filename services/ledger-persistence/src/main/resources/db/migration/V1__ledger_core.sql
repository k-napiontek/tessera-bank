-- The ledger schema.
--
-- Money is a bigint of minor units plus a char(3) ISO 4217 code. Never numeric, never a floating
-- point type. The canonical data model says money is minor units and a currency, and a numeric column
-- would invite a BigDecimal into the row mappers - which the domain forbids outright and enforces by
-- scanning its own sources for the word.
--
-- Forward-only. Once this file is merged it is never edited; a correction is a new migration.

-- ---------------------------------------------------------------------------------------------
-- account - what an account is, whose it is, and what may be posted to it.
--
-- It holds no balance, deliberately. WP-06 settled that: a balance is derived from postings, and
-- storing one on the account would create a second source of truth on day one. The balance table
-- below is a materialised read path, reconciled against the postings rather than trusted.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE account (
    reference             varchar(34)  NOT NULL,
    customer_ref          varchar(34)  NOT NULL,
    account_type          varchar(16)  NOT NULL,
    currency              char(3)      NOT NULL,
    status                varchar(16)  NOT NULL,

    -- NULL means OverdraftPolicy.forbidden(). A zero limit is a different thing - an account allowed
    -- to reach exactly zero - and conflating the two grants an overdraft of nothing to accounts that
    -- must never have one.
    overdraft_limit_minor bigint       NULL,

    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT account_pk PRIMARY KEY (reference)
);

-- ---------------------------------------------------------------------------------------------
-- journal_entry - one balanced set of postings.
--
-- created_at is persistence metadata, not domain state: JournalEntry carries a reference, a value
-- date and its postings, and nothing else. The audit trail is WP-09's and does not belong here.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE journal_entry (
    reference   varchar(34) NOT NULL,
    value_date  date        NOT NULL,

    -- Single currency per entry. JournalEntry rejects a mixed-currency entry outright, and holding the
    -- currency here lets the schema say the same thing with a foreign key instead of a trigger.
    currency    char(3)     NOT NULL,

    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT journal_entry_pk PRIMARY KEY (reference)
);

-- ---------------------------------------------------------------------------------------------
-- posting - one leg. Append-only; V2 enforces that with a trigger.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE posting (
    id           bigint      GENERATED ALWAYS AS IDENTITY,
    entry_ref    varchar(34) NOT NULL,

    -- A list has an order and a table does not. seq preserves the order of JournalEntry.postings(),
    -- so leg 1 of a transfer reads back as leg 1.
    seq          integer     NOT NULL,

    account_ref  varchar(34) NOT NULL,
    direction    varchar(6)  NOT NULL,

    -- Always positive. Direction carries the sign, exactly as MOVEREC does at stratum 0.
    amount_minor bigint      NOT NULL,
    currency     char(3)     NOT NULL,

    CONSTRAINT posting_pk PRIMARY KEY (id),
    CONSTRAINT posting_entry_fk FOREIGN KEY (entry_ref) REFERENCES journal_entry (reference),
    CONSTRAINT posting_account_fk FOREIGN KEY (account_ref) REFERENCES account (reference),
    CONSTRAINT posting_seq_uq UNIQUE (entry_ref, seq)
);

-- Reconciliation sums by account; the API reads an account's history by date. Both go through here.
CREATE INDEX posting_account_ix ON posting (account_ref);

-- ---------------------------------------------------------------------------------------------
-- balance - the materialised booked balance.
--
-- A read optimisation and nothing more. The authority is the sum of the postings, and
-- BalanceReconciliation proves the two agree rather than assuming it. That is the whole of
-- REQ-LED-006: a materialised figure is a second source of truth, so it gets checked.
--
-- Available balance is absent on purpose. It is booked less active holds, derived on read by
-- Balance.of - storing it would drift the first time a hold was captured without recomputing, and a
-- customer would be told they can spend money they cannot.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE balance (
    account_ref  varchar(34) NOT NULL,
    booked_minor bigint      NOT NULL DEFAULT 0,
    currency     char(3)     NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT balance_pk PRIMARY KEY (account_ref),
    CONSTRAINT balance_account_fk FOREIGN KEY (account_ref) REFERENCES account (reference)
);

-- ---------------------------------------------------------------------------------------------
-- hold - an amount reserved against an account but not yet posted.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE hold (
    reference    varchar(34) NOT NULL,
    account_ref  varchar(34) NOT NULL,
    amount_minor bigint      NOT NULL,
    currency     char(3)     NOT NULL,
    status       varchar(16) NOT NULL,
    placed_at    timestamptz NOT NULL,
    expires_at   timestamptz NULL,

    -- Set when and only when the hold was captured, which V2 asserts.
    captured_by  varchar(34) NULL,

    CONSTRAINT hold_pk PRIMARY KEY (reference),
    CONSTRAINT hold_account_fk FOREIGN KEY (account_ref) REFERENCES account (reference),
    CONSTRAINT hold_captured_by_fk FOREIGN KEY (captured_by) REFERENCES journal_entry (reference)
);

-- Balance.of needs the active holds for an account on every balance read.
CREATE INDEX hold_account_ix ON hold (account_ref, status);
