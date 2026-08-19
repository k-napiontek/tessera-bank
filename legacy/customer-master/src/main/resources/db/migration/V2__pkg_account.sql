-- ---------------------------------------------------------------------------------------------
-- PKG_ACCOUNT - the read side.
--
-- Business logic in stored procedures, where a 2011 team put it. The Java above this package
-- opens no cursor of its own and writes no SQL: it calls a procedure and maps what comes back,
-- which is why a later migration off Oracle is genuinely difficult rather than merely tedious.
-- That difficulty is the point - see TD-005.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE PACKAGE pkg_account AS

  -- The fault the WSDL names. ORA-20001 carries it, so the SOAP layer maps a number to a code
  -- rather than reading an error message and guessing what happened.
  acct_not_found EXCEPTION;
  PRAGMA EXCEPTION_INIT(acct_not_found, -20001);

  PROCEDURE get_account(p_account_ref IN VARCHAR2, p_result OUT SYS_REFCURSOR);

  PROCEDURE get_accounts_by_customer(p_customer_ref IN VARCHAR2, p_result OUT SYS_REFCURSOR);

END pkg_account;
/

CREATE OR REPLACE PACKAGE BODY pkg_account AS

  PROCEDURE get_account(p_account_ref IN VARCHAR2, p_result OUT SYS_REFCURSOR) IS
    v_exists NUMBER;
  BEGIN
    -- Asked and answered before the cursor is opened. A caller handed an empty cursor cannot tell
    -- "no such account" from "an account with nothing to say", and the WSDL requires the first to
    -- be a fault.
    SELECT COUNT(*)
      INTO v_exists
      FROM account
     WHERE account_ref = p_account_ref;

    IF v_exists = 0 THEN
      RAISE_APPLICATION_ERROR(-20001, 'ACCT_NOT_FOUND');
    END IF;

    OPEN p_result FOR
      SELECT account_ref,
             customer_ref,
             account_type,
             currency,
             status,
             booked_balance,
             opened_date,
             last_movement_date
        FROM account
       WHERE account_ref = p_account_ref;
  END get_account;

  PROCEDURE get_accounts_by_customer(p_customer_ref IN VARCHAR2, p_result OUT SYS_REFCURSOR) IS
  BEGIN
    -- No existence check, deliberately. The WSDL says an empty list is a valid answer and defines
    -- no fault about customers, so an unknown customer answers the same way a customer with no
    -- accounts does. Inventing a CUST_NOT_FOUND here would put a code on the wire that no contract
    -- declares.
    OPEN p_result FOR
      SELECT account_ref,
             customer_ref,
             account_type,
             currency,
             status,
             booked_balance,
             opened_date,
             last_movement_date
        FROM account
       WHERE customer_ref = p_customer_ref
       ORDER BY account_ref;
  END get_accounts_by_customer;

END pkg_account;
/
