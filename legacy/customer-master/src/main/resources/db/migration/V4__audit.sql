-- ---------------------------------------------------------------------------------------------
-- customer-master, schema version 4 - the operator audit trail.
--
-- Stratum 1 has had no audit table since WP-10a, and nothing noticed, because until WP-15 no
-- stratum 1 code mutated anything a person initiated. The back office is the first thing that does:
-- an operator acknowledges a reconciliation break, an operator annotates a reject. REQ-OPS-004 has
-- always required those to be attributable, and this is where they land.
--
-- APPEND-ONLY, enforced by a trigger rather than by convention. The same control the ledger's
-- audit_record carries three strata and twelve years away, and it is worth having twice: an audit
-- trail an application can rewrite is a log, not a control, and the difference only ever shows up
-- in the incident where it mattered.
--
-- The row is written INSIDE the transaction that makes the change. A trail written afterwards is
-- one that a rollback silently separates from the fact it describes.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE operator_audit (
  audit_id        NUMBER(18,0)   NOT NULL,
  occurred_at     TIMESTAMP(6)   DEFAULT SYSTIMESTAMP NOT NULL,
  actor           VARCHAR2(30)   NOT NULL,
  action          VARCHAR2(24)   NOT NULL,
  subject_type    VARCHAR2(16)   NOT NULL,
  subject_ref     VARCHAR2(64)   NOT NULL,
  business_date   DATE           NOT NULL,
  detail          VARCHAR2(400),
  CONSTRAINT operator_audit_pk PRIMARY KEY (audit_id),
  CONSTRAINT operator_audit_action_ck CHECK (action IN ('BREAK_ACKNOWLEDGED', 'REJECT_ANNOTATED')),
  CONSTRAINT operator_audit_subject_ck CHECK (subject_type IN ('BREAK', 'REJECT'))
);

-- Reading the trail is always "what happened to this thing", never "what happened at 14:32", so the
-- index follows the subject. An operator investigating a break wants its history, and an auditor
-- sampling a control wants the same thing for a row they picked.
CREATE INDEX operator_audit_subject_ix
  ON operator_audit (subject_type, subject_ref, business_date);

CREATE SEQUENCE operator_audit_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

-- ---------------------------------------------------------------------------------------------
-- What an operator has done. Both tables are plain DDL and are declared BEFORE the first PL/SQL
-- block on purpose: a script whose /-delimited chunks each hold either plain statements or exactly
-- one block is one that every reader in this estate splits the same way. Mixing them cost an
-- afternoon - see F-61.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE break_acknowledgement (
  business_date   DATE          NOT NULL,
  account_ref     VARCHAR2(16)  NOT NULL,
  classification  VARCHAR2(20)  NOT NULL,
  acknowledged_by VARCHAR2(30)  NOT NULL,
  acknowledged_at TIMESTAMP(6)  DEFAULT SYSTIMESTAMP NOT NULL,
  note            VARCHAR2(400),
  CONSTRAINT break_ack_pk PRIMARY KEY (business_date, account_ref),
  CONSTRAINT break_ack_class_ck CHECK (classification IN
    ('VALUE_DRIFT', 'MISSING_ON_MASTER', 'MISSING_IN_LEDGER', 'TIMING'))
);

CREATE TABLE reject_annotation (
  business_date   DATE          NOT NULL,
  transfer_ref    VARCHAR2(20)  NOT NULL,
  leg_no          NUMBER(2,0)   NOT NULL,
  annotated_by    VARCHAR2(30)  NOT NULL,
  annotated_at    TIMESTAMP(6)  DEFAULT SYSTIMESTAMP NOT NULL,
  note            VARCHAR2(400) NOT NULL,
  CONSTRAINT reject_annotation_pk PRIMARY KEY (business_date, transfer_ref, leg_no)
);
