      *================================================================*
      * MOVEREC - MOVEMENT RECORD                                      *
      *                                                                *
      * LENGTH      120 BYTES, FIXED                                   *
      * ORGANISATION  SEQUENTIAL, ASCENDING BY MOV-ACCT-REF            *
      * SOURCE      docs/architecture/canonical-data-model.md          *
      *                                                                *
      * ONE LEG OF A DOUBLE-ENTRY POSTING.  A TRANSFER PRODUCES TWO:   *
      * LEG 01 IS THE DEBIT, LEG 02 IS THE CREDIT.  THE PAIR SHARES ONE*
      * MOV-TRANSFER-REF, WHICH IS HOW THE BATCH REBUILDS A TRANSFER   *
      * THAT THIS TIER HAS NO RECORD FOR.                              *
      *                                                                *
      * MOV-AMOUNT IS ALWAYS POSITIVE.  MOV-DIRECTION CARRIES THE SIGN:*
      * 'D' DEBIT, 'C' CREDIT.                                         *
      *                                                                *
      * WRITTEN BY THE INTEGRATION TIER, SORTED, THEN APPLIED TO THE   *
      * ACCOUNT MASTER BY THE OVERNIGHT MATCH-MERGE.                   *
      *================================================================*
       01  MOVEMENT-RECORD.
           05  MOV-TRANSFER-REF      PIC X(20).
           05  MOV-LEG-NO            PIC 9(02).
           05  MOV-ACCT-REF          PIC X(16).
           05  MOV-DIRECTION         PIC X(01).
           05  MOV-CURRENCY          PIC X(03).
           05  MOV-AMOUNT            PIC S9(13)V99 COMP-3.
           05  MOV-VALUE-DATE        PIC 9(08).
           05  MOV-POSTED-TS         PIC 9(14).
           05  MOV-REFERENCE         PIC X(35).
           05  FILLER                PIC X(13).
