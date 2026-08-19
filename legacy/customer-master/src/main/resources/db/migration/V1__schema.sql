-- ---------------------------------------------------------------------------------------------
-- customer-master, schema version 1.
--
-- Oracle dialect, deliberately: VARCHAR2 rather than VARCHAR, NUMBER rather than NUMERIC,
-- REGEXP_LIKE check constraints, sequences rather than an identity column. The dialect lock-in is
-- the realistic part - see TD-005 - and it is what makes a later migration exercise genuinely hard.
--
-- Money is NUMBER(15,0): a signed count of MINOR UNITS, fifteen digits, matching AmountMinorType in
-- the canonical schema and PIC S9(13)V99 COMP-3 on the mainframe. Never NUMBER(15,2) and never a
-- BINARY_DOUBLE. Oracle would accept either, and the scale would then live in two places that can
-- disagree.
-- ---------------------------------------------------------------------------------------------

-- ---------------------------------------------------------------------------------------------
-- The only table in this estate that holds personal data, and it holds it on purpose: every other
-- component works from a customerRef, so every other component is out of scope for an erasure
-- request. See docs/compliance/gdpr-data-map.md. Every value is synthetic and generated.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE customer (
  customer_ref     VARCHAR2(12)  NOT NULL,
  family_name      VARCHAR2(70)  NOT NULL,
  given_name       VARCHAR2(70)  NOT NULL,
  date_of_birth    DATE          NOT NULL,
  national_id      VARCHAR2(20)  NOT NULL,
  onboarded_date   DATE          NOT NULL,
  CONSTRAINT customer_pk PRIMARY KEY (customer_ref),
  CONSTRAINT customer_ref_ck CHECK (REGEXP_LIKE(customer_ref, '^CU[0-9]{10}$'))
);

-- ---------------------------------------------------------------------------------------------
-- Account metadata, and a balance.
--
-- The balance is here because the contract puts it here: tb:Account makes bookedBalance mandatory
-- and GetAccount returns a tb:Account, while stratum 1 has no way to ask stratum 3 for it. So this
-- table is a second source of truth for money, deliberately - see ADR 0010. It is the drift that
-- batch/recon exists to find.
--
-- There is no available_balance column. A hold lives only in the ledger and no notification carries
-- one, so available equals booked at this tier; a column that is definitionally a copy of another
-- is a place for the two to disagree. The endpoint says so on the response.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE account (
  account_ref         VARCHAR2(16)  NOT NULL,
  customer_ref        VARCHAR2(12)  NOT NULL,
  account_type        VARCHAR2(9)   NOT NULL,
  currency            CHAR(3)       NOT NULL,
  status              VARCHAR2(7)   NOT NULL,
  booked_balance      NUMBER(15,0)  DEFAULT 0 NOT NULL,
  opened_date         DATE          NOT NULL,
  last_movement_date  DATE,
  CONSTRAINT account_pk PRIMARY KEY (account_ref),
  CONSTRAINT account_customer_fk FOREIGN KEY (customer_ref) REFERENCES customer (customer_ref),
  CONSTRAINT account_ref_ck CHECK (REGEXP_LIKE(account_ref, '^TB[0-9A-Z]{14}$')),
  CONSTRAINT account_currency_ck CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
  CONSTRAINT account_type_ck CHECK (account_type IN
    ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
  CONSTRAINT account_status_ck CHECK (status IN ('OPEN', 'BLOCKED', 'CLOSED')),
  CONSTRAINT account_movement_ck CHECK (last_movement_date IS NULL OR last_movement_date >= opened_date)
);

CREATE INDEX account_customer_ix ON account (customer_ref);

-- ---------------------------------------------------------------------------------------------
-- What makes NotifyTransferPosted idempotent.
--
-- The primary key is the mechanism, not a record of one: two deliveries of the same transfer race
-- to insert, and Oracle refuses the second. A SELECT-then-INSERT would let both find nothing and
-- both apply, which is a customer's payment applied twice - the failure this table exists to
-- prevent. Upstream delivery is at-least-once and says so in the AsyncAPI contract.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE applied_transfer (
  transfer_ref    VARCHAR2(20)  NOT NULL,
  correlation_id  VARCHAR2(36),
  applied_at      TIMESTAMP(6)  DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT applied_transfer_pk PRIMARY KEY (transfer_ref),
  CONSTRAINT applied_transfer_ref_ck CHECK (REGEXP_LIKE(transfer_ref, '^TB[0-9]{18}$'))
);

-- ---------------------------------------------------------------------------------------------
-- The numbering series. customer-master owns onboarding, so it allocates both references and the
-- ledger accepts whatever it is given - which is what lets POST /accounts be idempotent without an
-- Idempotency-Key header. NOCACHE because a gap in a customer number is the kind of thing an
-- operations team raises a ticket about, and the throughput here is onboarding, not payments.
-- ---------------------------------------------------------------------------------------------
CREATE SEQUENCE customer_ref_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;
CREATE SEQUENCE account_ref_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;
