-- ---------------------------------------------------------------------------------------------
-- PKG_POSTING - what NotifyTransferPosted does to the master.
--
-- The ledger has ALREADY posted by the time this is called. That single fact decides most of the
-- behaviour below: this package mirrors a movement that has happened, it does not decide whether
-- one may. So an account's status is not consulted - refusing a completed posting does not unmake
-- it, it only leaves this master permanently wrong about that account, and it hides the
-- disagreement from batch/recon by never recording it. A block belongs before a payment, where it
-- can still prevent one.
--
-- What DOES raise is an integrity failure: an account the master has never heard of, a currency
-- that cannot be added to the balance it is aimed at, an amount that is not positive, or both legs
-- naming one account. Those mean the two systems disagree about the world rather than about money,
-- and WP-11's dead-letter path exists for exactly that.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE PACKAGE pkg_posting AS

  acct_not_found          EXCEPTION;
  acct_currency_mismatch  EXCEPTION;
  amount_not_positive     EXCEPTION;
  same_account            EXCEPTION;
  PRAGMA EXCEPTION_INIT(acct_not_found,         -20001);
  PRAGMA EXCEPTION_INIT(acct_currency_mismatch, -20002);
  PRAGMA EXCEPTION_INIT(amount_not_positive,    -20003);
  PRAGMA EXCEPTION_INIT(same_account,           -20004);

  -- p_already_applied is 1 when this transfer had been applied before, and the caller treats that
  -- as success: at-least-once delivery makes a duplicate an expected event, not an error.
  PROCEDURE apply_transfer(
    p_transfer_ref       IN  VARCHAR2,
    p_correlation_id     IN  VARCHAR2,
    p_debit_account_ref  IN  VARCHAR2,
    p_credit_account_ref IN  VARCHAR2,
    p_amount_minor       IN  NUMBER,
    p_currency           IN  VARCHAR2,
    p_value_date         IN  DATE,
    p_already_applied    OUT NUMBER);

END pkg_posting;
/

CREATE OR REPLACE PACKAGE BODY pkg_posting AS

  -- ---------------------------------------------------------------------------------------------
  -- The sign convention, in one place.
  --
  -- "Debit" does not mean "minus". A customer current account is a LIABILITY of the bank and falls
  -- on a debit; the bank's own cash is an ASSET and rises on one. The same rule lives in
  -- AccountType.signedEffect in the ledger's Java, arrived at there from a failing test. Written as
  -- minus-for-debit this package would balance perfectly and report half the estate's money with
  -- the wrong sign.
  -- ---------------------------------------------------------------------------------------------
  FUNCTION signed_effect(
    p_account_type IN VARCHAR2,
    p_direction    IN VARCHAR2,
    p_amount_minor IN NUMBER) RETURN NUMBER IS
    v_debit_is_positive BOOLEAN;
  BEGIN
    v_debit_is_positive := p_account_type IN ('ASSET', 'EXPENSE');

    IF p_direction = 'DEBIT' THEN
      RETURN CASE WHEN v_debit_is_positive THEN p_amount_minor ELSE -p_amount_minor END;
    ELSE
      RETURN CASE WHEN v_debit_is_positive THEN -p_amount_minor ELSE p_amount_minor END;
    END IF;
  END signed_effect;

  PROCEDURE apply_leg(
    p_account_ref  IN VARCHAR2,
    p_direction    IN VARCHAR2,
    p_amount_minor IN NUMBER,
    p_currency     IN VARCHAR2,
    p_value_date   IN DATE) IS
    v_account_type account.account_type%TYPE;
    v_currency     account.currency%TYPE;
    v_effect       NUMBER;
  BEGIN
    BEGIN
      SELECT account_type, currency
        INTO v_account_type, v_currency
        FROM account
       WHERE account_ref = p_account_ref
         FOR UPDATE;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20001, 'ACCT_NOT_FOUND: ' || p_account_ref);
    END;

    IF v_currency <> p_currency THEN
      -- No exchange rate exists anywhere in this estate and no tier converts. Adding minor units
      -- of one currency to a balance held in another produces a number that means nothing.
      RAISE_APPLICATION_ERROR(-20002,
        'ACCT_CURRENCY_MISMATCH: ' || p_account_ref || ' holds ' || v_currency
        || ', movement is ' || p_currency);
    END IF;

    -- Computed in PL/SQL and then used as a bind. A package's own private function may not be
    -- called from a SQL statement inside that package - PLS-00231 - and the body compiles to an
    -- INVALID object rather than failing the CREATE, so the mistake surfaces as ORA-04063 from
    -- whatever calls it first. SchemaTest.leavesNoInvalidObjectBehind is what catches that.
    v_effect := signed_effect(v_account_type, p_direction, p_amount_minor);

    UPDATE account
       SET booked_balance = booked_balance + v_effect,
           -- GREATEST, because a backdated correction is a normal event and it must not drag the
           -- last movement date backwards.
           last_movement_date = GREATEST(NVL(last_movement_date, p_value_date), p_value_date)
     WHERE account_ref = p_account_ref;
  END apply_leg;

  PROCEDURE apply_transfer(
    p_transfer_ref       IN  VARCHAR2,
    p_correlation_id     IN  VARCHAR2,
    p_debit_account_ref  IN  VARCHAR2,
    p_credit_account_ref IN  VARCHAR2,
    p_amount_minor       IN  NUMBER,
    p_currency           IN  VARCHAR2,
    p_value_date         IN  DATE,
    p_already_applied    OUT NUMBER) IS
  BEGIN
    IF p_amount_minor IS NULL OR p_amount_minor <= 0 THEN
      -- Direction carries the sign; the amount is always positive. A negative amount here would
      -- silently reverse a payment.
      RAISE_APPLICATION_ERROR(-20003, 'AMOUNT_NOT_POSITIVE');
    END IF;

    IF p_debit_account_ref = p_credit_account_ref THEN
      RAISE_APPLICATION_ERROR(-20004, 'SAME_ACCOUNT: ' || p_debit_account_ref);
    END IF;

    -- The claim comes first, and it is an INSERT rather than a SELECT. Two deliveries of the same
    -- transfer race here; the loser gets DUP_VAL_ON_INDEX and applies nothing. A read-then-write
    -- would let both find nothing and both apply, which is a customer's payment taken twice.
    BEGIN
      INSERT INTO applied_transfer (transfer_ref, correlation_id)
      VALUES (p_transfer_ref, p_correlation_id);
    EXCEPTION
      WHEN DUP_VAL_ON_INDEX THEN
        p_already_applied := 1;
        RETURN;
    END;

    apply_leg(p_debit_account_ref,  'DEBIT',  p_amount_minor, p_currency, p_value_date);
    apply_leg(p_credit_account_ref, 'CREDIT', p_amount_minor, p_currency, p_value_date);

    p_already_applied := 0;
  END apply_transfer;

END pkg_posting;
/
