      *================================================================*
      * ACCTREC - ACCOUNT MASTER RECORD                                *
      *                                                                *
      * LENGTH      100 BYTES, FIXED                                   *
      * ORGANISATION  SEQUENTIAL, ASCENDING BY ACCT-REF                *
      * SOURCE      docs/architecture/canonical-data-model.md          *
      *                                                                *
      * THE DEFINITIVE RECORD OF WHAT EVERY ACCOUNT CONTAINS.  READ AND*
      * REWRITTEN BY THE OVERNIGHT CYCLE, READ BY RECONCILIATION.      *
      *                                                                *
      * BALANCES ARE COMP-3 PACKED DECIMAL, 8 BYTES, SIGN NIBBLE X'0C' *
      * POSITIVE AND X'0D' NEGATIVE.  ZERO IS ALWAYS POSITIVE.         *
      *                                                                *
      * THE IMPLIED DECIMAL OF V99 ASSUMES A CURRENCY OF SCALE 2.  THE *
      * INTEGRATION TIER REJECTS ANY OTHER SCALE BEFORE IT GETS HERE.  *
      *                                                                *
      * DO NOT REORDER OR RESIZE A FIELD.  A NEW FIELD TAKES BYTES FROM*
      * FILLER AND THE RECORD LENGTH NEVER CHANGES.                    *
      *================================================================*
       01  ACCOUNT-RECORD.
           05  ACCT-REF              PIC X(16).
           05  ACCT-CUST-REF         PIC X(12).
           05  ACCT-TYPE             PIC X(09).
           05  ACCT-CURRENCY         PIC X(03).
           05  ACCT-STATUS           PIC X(07).
           05  ACCT-BOOKED-BAL       PIC S9(13)V99 COMP-3.
           05  ACCT-AVAIL-BAL        PIC S9(13)V99 COMP-3.
           05  ACCT-OPENED-DATE      PIC 9(08).
           05  ACCT-LAST-MOVE-DATE   PIC 9(08).
           05  FILLER                PIC X(21).
