-- ---------------------------------------------------------------------------------------------
-- customer-master, schema version 5 - the operator audit trail's PL/SQL.
--
-- Separate from V4's tables, following V2 and V3: one file per PL/SQL package at this stratum.
-- The convention is not only tidiness. Every reader of these scripts splits them on a line
-- containing a single `/`, and a file that mixes plain DDL with a PL/SQL block puts both in one
-- chunk - where the block's own semicolons get treated as statement terminators and Oracle answers
-- ORA-00900 naming a fragment nobody wrote. Stratum 1's own SqlScript is robust to that; the copy
-- stratum 2's tests carry is not, which is F-61, and it is cheaper to keep the convention than to
-- rely on every reader being the careful one.
-- ---------------------------------------------------------------------------------------------

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
