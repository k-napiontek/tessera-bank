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
-- Append-only. NOCACHE on the sequence above is deliberate too: gaps in an audit id are the kind
-- of thing an auditor asks about, and "the sequence cached twenty and the instance restarted" is a
-- worse answer than a slower insert.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE TRIGGER operator_audit_no_change
  BEFORE UPDATE OR DELETE ON operator_audit
BEGIN
  RAISE_APPLICATION_ERROR(
    -20010,
    'operator_audit is append-only: an audit trail an application can rewrite is a log, not a control');
END;
/

-- ---------------------------------------------------------------------------------------------
-- The operator's two actions, and the audit row each one writes.
--
-- Both live here rather than in Java for the reason the rest of this schema does: a 2011 team put
-- business logic in the database, and an audit row written by application code is one an
-- application bug can skip. Here the change and its record are the same statement pair in the same
-- transaction, and there is no path to one without the other.
--
-- Acknowledging is idempotent. An operator who double-clicks has not performed two acts, and a
-- trail that says they did is a trail that misleads the person reading it later.
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

CREATE OR REPLACE PACKAGE pkg_operator AS

  timing_not_actionable   EXCEPTION;
  note_required           EXCEPTION;
  PRAGMA EXCEPTION_INIT(timing_not_actionable, -20011);
  PRAGMA EXCEPTION_INIT(note_required,         -20012);

  PROCEDURE acknowledge_break(
    p_business_date  IN DATE,
    p_account_ref    IN VARCHAR2,
    p_classification IN VARCHAR2,
    p_actor          IN VARCHAR2,
    p_note           IN VARCHAR2);

  PROCEDURE annotate_reject(
    p_business_date IN DATE,
    p_transfer_ref  IN VARCHAR2,
    p_leg_no        IN NUMBER,
    p_actor         IN VARCHAR2,
    p_note          IN VARCHAR2);

END pkg_operator;
/

CREATE OR REPLACE PACKAGE BODY pkg_operator AS

  PROCEDURE write_audit(
    p_action       IN VARCHAR2,
    p_subject_type IN VARCHAR2,
    p_subject_ref  IN VARCHAR2,
    p_business_date IN DATE,
    p_actor        IN VARCHAR2,
    p_detail       IN VARCHAR2)
  IS
  BEGIN
    INSERT INTO operator_audit (
      audit_id, actor, action, subject_type, subject_ref, business_date, detail)
    VALUES (
      operator_audit_seq.NEXTVAL, p_actor, p_action, p_subject_type, p_subject_ref,
      p_business_date, p_detail);
  END write_audit;

  -- -------------------------------------------------------------------------------------------
  -- A TIMING break is expected and is not the operator's to work - see ADR 0015 and the
  -- reconciliation-break runbook. Refusing it here rather than only hiding the button matters:
  -- the screen is not the only way to reach this package, and a control that lives in a JSP is a
  -- control that a second caller does not have.
  -- -------------------------------------------------------------------------------------------
  PROCEDURE acknowledge_break(
    p_business_date  IN DATE,
    p_account_ref    IN VARCHAR2,
    p_classification IN VARCHAR2,
    p_actor          IN VARCHAR2,
    p_note           IN VARCHAR2)
  IS
  BEGIN
    IF p_classification = 'TIMING' THEN
      RAISE_APPLICATION_ERROR(
        -20011,
        'a TIMING break is expected and is not acknowledged; see ADR 0015');
    END IF;

    -- Idempotent on purpose: a double-click is one act, and a trail that records two misleads
    -- whoever reads it afterwards.
    MERGE INTO break_acknowledgement t
    USING (SELECT p_business_date AS business_date, p_account_ref AS account_ref FROM dual) s
       ON (t.business_date = s.business_date AND t.account_ref = s.account_ref)
     WHEN NOT MATCHED THEN
       INSERT (business_date, account_ref, classification, acknowledged_by, note)
       VALUES (p_business_date, p_account_ref, p_classification, p_actor, p_note);

    IF SQL%ROWCOUNT > 0 THEN
      write_audit('BREAK_ACKNOWLEDGED', 'BREAK', p_account_ref, p_business_date, p_actor, p_note);
    END IF;
  END acknowledge_break;

  PROCEDURE annotate_reject(
    p_business_date IN DATE,
    p_transfer_ref  IN VARCHAR2,
    p_leg_no        IN NUMBER,
    p_actor         IN VARCHAR2,
    p_note          IN VARCHAR2)
  IS
  BEGIN
    -- An annotation with no text is not an annotation. The screen should not send one; this is
    -- what makes that true for every caller.
    --
    -- TRIM(p_note) IS NULL, not LENGTH(TRIM(p_note)) = 0. In Oracle the empty string IS NULL, so
    -- TRIM('   ') is NULL, LENGTH(NULL) is NULL, and NULL = 0 is NULL rather than TRUE - the check
    -- compiles, runs, and silently accepts the value it was written to refuse. The first version
    -- of this procedure had it the other way round and the test caught it.
    IF p_note IS NULL OR TRIM(p_note) IS NULL THEN
      RAISE_APPLICATION_ERROR(-20012, 'an annotation needs a note');
    END IF;

    MERGE INTO reject_annotation t
    USING (SELECT p_business_date AS business_date,
                  p_transfer_ref  AS transfer_ref,
                  p_leg_no        AS leg_no FROM dual) s
       ON (t.business_date = s.business_date
           AND t.transfer_ref = s.transfer_ref
           AND t.leg_no = s.leg_no)
     WHEN MATCHED THEN
       UPDATE SET note = p_note, annotated_by = p_actor, annotated_at = SYSTIMESTAMP
     WHEN NOT MATCHED THEN
       INSERT (business_date, transfer_ref, leg_no, annotated_by, note)
       VALUES (p_business_date, p_transfer_ref, p_leg_no, p_actor, p_note);

    -- Every annotation is audited, including a re-annotation: replacing the note IS the act, and
    -- the previous text survives only in the trail.
    write_audit('REJECT_ANNOTATED', 'REJECT', p_transfer_ref || '/' || TO_CHAR(p_leg_no),
                p_business_date, p_actor, p_note);
  END annotate_reject;

END pkg_operator;
/
