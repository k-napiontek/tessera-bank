      *================================================================*
      * EODREPT - END OF DAY ACCOUNT REPORT                            *
      *                                                                *
      * THE SECOND CANONICAL BATCH PATTERN AFTER MATCH-MERGE: A        *
      * CONTROL-BREAK REPORT.  ONE SEQUENTIAL PASS OVER THE ACCOUNT    *
      * MASTER IN REPORT SEQUENCE, PAGE-BROKEN FOR A LINE PRINTER.     *
      *                                                                *
      * INPUT   ACCTRPT.DAT   ACCTREC, 100 BYTES, ASCENDING BY         *
      *                       CURRENCY THEN ACCT-REF.  STEP030 OF THE  *
      *                       CYCLE SORTS IT INTO THAT ORDER - THE     *
      *                       REPORT NEVER SORTS AND NEVER HOLDS THE   *
      *                       FILE, EXACTLY AS ACCTPOST DOES NOT.      *
      * OUTPUT  EODREPT.TXT   132-COLUMN PRINT LINES, FORM FEED AT     *
      *                       EVERY PAGE BREAK.                        *
      *                                                                *
      * THE PRINT FILE IS LINE SEQUENTIAL AND IT IS THE ONE FILE IN    *
      * THIS TIER THAT SHOULD BE.  THE RULE THAT COMP-3 BYTES FORBID   *
      * LINE SEQUENTIAL IS ABOUT DATA FILES; A PRINTED REPORT HOLDS NO *
      * PACKED FIELD, AND A LINE PRINTER PRODUCES LINES.               *
      *                                                                *
      * THE BUSINESS DATE AND RUN TIMESTAMP COME FROM THE JOB VIA      *
      * EODREPT_BUS_DATE AND EODREPT_RUN_TS, FALLING BACK TO THE       *
      * CLOCK.  A BATCH JOB TAKES ITS BUSINESS DATE FROM JCL, AND IT   *
      * IS WHAT LETS TWO RUNS OF THE CYCLE PRODUCE IDENTICAL BYTES.    *
      *                                                                *
      * COMPILE:                                                       *
      *   cobc -x -std=ibm -Wall -I mainframe/copybook EODREPT.CBL     *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. EODREPT.
       AUTHOR. TESSERA BANK CORE BANKING.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.

           SELECT ACCT-REPORT-IN  ASSIGN TO 'ACCTRPT.DAT'
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-RPTIN-STATUS.

           SELECT REJECT-IN       ASSIGN TO 'REJECTS.DAT'
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-REJIN-STATUS.

           SELECT PRINT-FILE      ASSIGN TO 'EODREPT.TXT'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-PRINT-STATUS.

       DATA DIVISION.
       FILE SECTION.

       FD  ACCT-REPORT-IN
           RECORD CONTAINS 100 CHARACTERS.
       01  REPORT-IN-RECORD        PIC X(100).

       FD  REJECT-IN
           RECORD CONTAINS 200 CHARACTERS.
       01  REJECT-IN-RECORD        PIC X(200).

      *    NO RECORD CLAUSE ON THE PRINT FILE.  LINE SEQUENTIAL TAKES
      *    ITS LENGTH FROM THE RECORD AND COBC WARNS THAT THE CLAUSE IS
      *    IGNORED; THE 132 COLUMNS LIVE IN PIC X(132) INSTEAD.
       FD  PRINT-FILE.
       01  PRINT-LINE              PIC X(132).

       WORKING-STORAGE SECTION.

       COPY ACCTREC.

       COPY REJREC.

       01  WS-FILE-STATUS.
           05  WS-RPTIN-STATUS       PIC X(02) VALUE SPACES.
           05  WS-REJIN-STATUS       PIC X(02) VALUE SPACES.
           05  WS-PRINT-STATUS       PIC X(02) VALUE SPACES.

       01  WS-FLAGS.
           05  WS-RPTIN-EOF          PIC X(01) VALUE 'N'.
               88  RPTIN-EOF                   VALUE 'Y'.
           05  WS-REJIN-EOF          PIC X(01) VALUE 'N'.
               88  REJIN-EOF                   VALUE 'Y'.

      *    PAGE CONTROL.  SIXTY LINES TO A PAGE, SIX OF THEM HEADER,
      *    LEAVING FIFTY-FOUR FOR THE BODY.  AN OFF-BY-ONE HERE PRINTS
      *    A REPORT THAT LOOKS ENTIRELY NORMAL UNTIL SOMEONE COUNTS.
       01  WS-PAGE-NO                PIC 9(04) VALUE ZERO.
       01  WS-BODY-LINES             PIC 9(03) VALUE ZERO.
       01  WS-BODY-LIMIT             PIC 9(03) VALUE 54.
       01  WS-LINES-NEEDED           PIC 9(03) VALUE ZERO.
       01  WS-PAGE-BREAK-WANTED      PIC X(01) VALUE 'Y'.
           88  PAGE-BREAK-WANTED               VALUE 'Y'.

      *    THE CONTROL BREAK.  THE INPUT ARRIVES SORTED BY CURRENCY, SO
      *    A CHANGE OF CURRENCY CLOSES THE PREVIOUS ONE.  THE
      *    ACCUMULATORS ARE TWO DIGITS WIDER THAN A BALANCE BECAUSE
      *    THEY HOLD A SUM OF BALANCES, AND THEY CARRY V99 BECAUSE THE
      *    MONEY THEY HOLD DOES.  AN ACCUMULATOR WITHOUT V99 TRUNCATES
      *    EVERY SUBTOTAL TO WHOLE UNITS AND PRINTS A FIGURE THAT LOOKS
      *    ENTIRELY REASONABLE.
       01  WS-CURRENT-CCY            PIC X(03) VALUE SPACES.
       01  WS-CCY-COUNT              PIC 9(09) COMP-3 VALUE ZERO.
       01  WS-CCY-BOOKED         PIC S9(15)V99 COMP-3 VALUE ZERO.
       01  WS-CCY-AVAIL          PIC S9(15)V99 COMP-3 VALUE ZERO.

       01  WS-BUS-DATE               PIC X(08) VALUE SPACES.
       01  WS-RUN-TS                 PIC X(14) VALUE SPACES.
       01  WS-CURRENT-DATE-DATA.
           05  WS-CD-DATE            PIC 9(08).
           05  WS-CD-TIME            PIC 9(06).
           05  FILLER                PIC X(07).

       01  WS-DATE-IN.
           05  WS-DI-YYYY            PIC 9(04).
           05  WS-DI-MM              PIC 9(02).
           05  WS-DI-DD              PIC 9(02).

       01  WS-DATE-ED.
           05  WS-DE-YYYY            PIC 9(04).
           05  FILLER                PIC X(01) VALUE '-'.
           05  WS-DE-MM              PIC 9(02).
           05  FILLER                PIC X(01) VALUE '-'.
           05  WS-DE-DD              PIC 9(02).

       01  WS-TS-IN.
           05  WS-TI-DATE            PIC 9(08).
           05  WS-TI-HH              PIC 9(02).
           05  WS-TI-MI              PIC 9(02).
           05  WS-TI-SS              PIC 9(02).

       01  WS-TS-ED.
           05  WS-TE-DATE            PIC X(10).
           05  FILLER                PIC X(01) VALUE SPACE.
           05  WS-TE-HH              PIC 9(02).
           05  FILLER                PIC X(01) VALUE ':'.
           05  WS-TE-MI              PIC 9(02).
           05  FILLER                PIC X(01) VALUE ':'.
           05  WS-TE-SS              PIC 9(02).

       01  WS-RETURN-CODE            PIC 9(02) VALUE ZERO.
       01  WS-COUNT-ED               PIC ZZZ,ZZZ,ZZ9.
       01  WS-IX                     PIC 9(02) VALUE ZERO.
       01  WS-FOUND                  PIC X(01) VALUE 'N'.
       01  WS-GRAND-COUNT            PIC 9(09) COMP-3 VALUE ZERO.

      *    TWO SMALL TABLES, AND ONLY SMALL ONES.  A HANDFUL OF
      *    CURRENCIES AND A HANDFUL OF REASON CODES FIT IN MEMORY; THE
      *    ACCOUNT MASTER DOES NOT, WHICH IS WHY NEITHER THIS PROGRAM
      *    NOR ACCTPOST EVER HOLDS IT.
       01  WS-CCY-TABLE.
           05  WS-CCY-ENTRY OCCURS 20 TIMES.
               10  WS-CE-CCY         PIC X(03).
               10  WS-CE-COUNT       PIC 9(09) COMP-3.
               10  WS-CE-BOOKED  PIC S9(15)V99 COMP-3.
               10  WS-CE-AVAIL   PIC S9(15)V99 COMP-3.
       01  WS-CCY-ENTRIES            PIC 9(02) VALUE ZERO.
       01  WS-CCY-MAX                PIC 9(02) VALUE 20.

      *    THE REASON TEXT IS TAKEN FROM THE REJECT RECORD, NEVER
      *    RESTATED HERE.  THE SIX CODES BELONG TO ACCTPOST; A SECOND
      *    COPY OF THEM IN THIS PROGRAM IS A SECOND COPY TO DRIFT.
       01  WS-RJ-TABLE.
           05  WS-RJ-ENTRY OCCURS 10 TIMES.
               10  WS-RE-CODE        PIC X(04).
               10  WS-RE-TEXT        PIC X(40).
               10  WS-RE-COUNT       PIC 9(09) COMP-3.
       01  WS-RJ-ENTRIES             PIC 9(02) VALUE ZERO.
       01  WS-RJ-MAX                 PIC 9(02) VALUE 10.
       01  WS-RJ-TOTAL               PIC 9(09) COMP-3 VALUE ZERO.

       01  WS-CTL-REJECTED-TXT       PIC X(12) VALUE SPACES.
       01  WS-CTL-REJECTED           PIC 9(09) COMP-3 VALUE ZERO.
       01  WS-CTL-SUPPLIED           PIC X(01) VALUE 'N'.
           88  CTL-SUPPLIED                    VALUE 'Y'.

      *----------------------------------------------------------------*
      * THE PRINT LINES.  EVERY FIELD SITS IN A FIXED COLUMN BECAUSE
      * THIS IS READ ON PAPER, AND BECAUSE THE BACK OFFICE PARSES IT.
      *----------------------------------------------------------------*
       01  WS-BLANK-LINE             PIC X(132) VALUE SPACES.

       01  WS-HEAD-1.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(12) VALUE 'TESSERA BANK'.
           05  FILLER                PIC X(34) VALUE SPACES.
           05  FILLER                PIC X(25) VALUE
               'END OF DAY ACCOUNT REPORT'.
           05  FILLER                PIC X(44) VALUE SPACES.
           05  FILLER                PIC X(05) VALUE 'PAGE '.
           05  WS-H1-PAGE            PIC ZZZ9.
           05  FILLER                PIC X(06) VALUE SPACES.

       01  WS-HEAD-2.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(14) VALUE 'REPORT EODREPT'.
           05  FILLER                PIC X(04) VALUE SPACES.
           05  FILLER                PIC X(14) VALUE 'BUSINESS DATE '.
           05  WS-H2-BUS-DATE        PIC X(10).
           05  FILLER                PIC X(04) VALUE SPACES.
           05  FILLER                PIC X(04) VALUE 'RUN '.
           05  WS-H2-RUN-TS          PIC X(19).
           05  FILLER                PIC X(61) VALUE SPACES.

       01  WS-HEAD-3.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(17) VALUE
               'ACCOUNT REFERENCE'.
           05  FILLER                PIC X(01) VALUE SPACES.
           05  FILLER                PIC X(12) VALUE 'CUSTOMER'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(09) VALUE 'TYPE'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(07) VALUE 'STATUS'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(03) VALUE 'CCY'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE
               '         BOOKED BALANCE'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE
               '      AVAILABLE BALANCE'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(10) VALUE 'LAST MOVED'.
           05  FILLER                PIC X(13) VALUE SPACES.

       01  WS-HEAD-4.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(16) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(12) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(09) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(07) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(03) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(10) VALUE ALL '-'.
           05  FILLER                PIC X(13) VALUE SPACES.

      *    THE SIGN TRAILS THE DIGITS.  A MINUS PARKED NINE COLUMNS
      *    AWAY FROM ITS NUMBER IS A DEFECT ON A PRINTED PAGE.
      *
      *    THE MONEY COLUMNS ARE FIFTEEN DIGITS WIDE THOUGH A BALANCE
      *    HOLDS THIRTEEN, SO THAT A CURRENCY SUBTOTAL - WHICH IS A SUM
      *    OF BALANCES AND NEEDS THE EXTRA ROOM - PRINTS WITH THE SAME
      *    PICTURE AND UNDER THE SAME COLUMNS.  A NARROWER TOTAL FIELD
      *    WOULD DROP HIGH-ORDER DIGITS IN SILENCE.
       01  WS-DETAIL.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-REF             PIC X(16).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-CUST            PIC X(12).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-TYPE            PIC X(09).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-STATUS          PIC X(07).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-CCY             PIC X(03).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-BOOKED          PIC ZZZ,ZZZ,ZZZ,ZZZ,ZZ9.99-.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-AVAIL           PIC ZZZ,ZZZ,ZZZ,ZZZ,ZZ9.99-.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-DT-LAST-MOVE       PIC X(10).
           05  FILLER                PIC X(13) VALUE SPACES.

      *    THE SUBTOTAL SITS UNDER THE COLUMNS IT TOTALS, WHICH IS WHY
      *    THE DETAIL MONEY FIELDS WERE MADE FIFTEEN DIGITS WIDE.
       01  WS-CCY-TOTAL-LINE.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(19) VALUE
               '*** CURRENCY TOTAL '.
           05  WS-CT-CCY             PIC X(03).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-CT-COUNT           PIC ZZZ,ZZZ,ZZ9.
           05  FILLER                PIC X(09) VALUE ' ACCOUNTS'.
           05  FILLER                PIC X(13) VALUE SPACES.
           05  WS-CT-BOOKED          PIC ZZZ,ZZZ,ZZZ,ZZZ,ZZ9.99-.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-CT-AVAIL           PIC ZZZ,ZZZ,ZZZ,ZZZ,ZZ9.99-.
           05  FILLER                PIC X(25) VALUE SPACES.

       01  WS-RECAP-HEAD.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(18) VALUE
               '*** CURRENCY RECAP'.
           05  FILLER                PIC X(112) VALUE SPACES.

       01  WS-RECAP-COLS.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  FILLER                PIC X(03) VALUE 'CCY'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(12) VALUE '    ACCOUNTS'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE
               '           BOOKED TOTAL'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE
               '        AVAILABLE TOTAL'.
           05  FILLER                PIC X(59) VALUE SPACES.

       01  WS-RECAP-RULE.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  FILLER                PIC X(03) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(12) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(23) VALUE ALL '-'.
           05  FILLER                PIC X(59) VALUE SPACES.

       01  WS-RECAP-ROW.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  WS-RR-CCY             PIC X(03).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-RR-COUNT           PIC ZZZZ,ZZZ,ZZ9.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-RR-BOOKED          PIC ZZZ,ZZZ,ZZZ,ZZZ,ZZ9.99-.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-RR-AVAIL           PIC ZZZ,ZZZ,ZZZ,ZZZ,ZZ9.99-.
           05  FILLER                PIC X(59) VALUE SPACES.

      *    THE GRAND TOTAL COUNTS ACCOUNTS AND STOPS THERE.  ADDING
      *    100 PLN TO 100 EUR PRODUCES A NUMBER THAT MEANS NOTHING AND
      *    THAT NO AUDITOR WOULD ACCEPT, SO THE REPORT DOES NOT PRINT
      *    ONE AND SAYS WHY ON THE PAGE.
       01  WS-GRAND-LINE.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(15) VALUE '*** GRAND TOTAL'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-GT-COUNT           PIC ZZZZ,ZZZ,ZZ9.
           05  FILLER                PIC X(09) VALUE ' ACCOUNTS'.
           05  FILLER                PIC X(92) VALUE SPACES.

       01  WS-NO-XCCY-LINE.
           05  FILLER                PIC X(08) VALUE SPACES.
           05  FILLER                PIC X(67) VALUE
               'NO CROSS-CURRENCY AMOUNT IS PRINTED.  SEE THE CURRENCY R
      -        'ECAP ABOVE.'.
           05  FILLER                PIC X(57) VALUE SPACES.

       01  WS-REJECT-HEAD.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(16) VALUE '*** REJECT RECAP'.
           05  FILLER                PIC X(114) VALUE SPACES.

       01  WS-REJECT-COLS.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  FILLER                PIC X(04) VALUE 'CODE'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(40) VALUE 'REASON'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(12) VALUE '       COUNT'.
           05  FILLER                PIC X(66) VALUE SPACES.

       01  WS-REJECT-RULE.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  FILLER                PIC X(04) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(40) VALUE ALL '-'.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  FILLER                PIC X(12) VALUE ALL '-'.
           05  FILLER                PIC X(66) VALUE SPACES.

       01  WS-REJECT-ROW.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  WS-RJ-ROW-CODE        PIC X(04).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-RJ-ROW-TEXT        PIC X(40).
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-RJ-ROW-COUNT       PIC ZZZZ,ZZZ,ZZ9.
           05  FILLER                PIC X(66) VALUE SPACES.

       01  WS-REJECT-TOTAL-LINE.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  FILLER                PIC X(14) VALUE 'TOTAL REJECTED'.
           05  FILLER                PIC X(34) VALUE SPACES.
           05  WS-RJ-TOTAL-ED        PIC ZZZZ,ZZZ,ZZ9.
           05  FILLER                PIC X(66) VALUE SPACES.

      *    WHAT ACCTPOST SAID, BESIDE WHAT THIS PROGRAM COUNTED.  WHEN
      *    THE JOB SUPPLIES NO FIGURE THE LINE SAYS SO: A REPORT THAT
      *    PRINTS A RECONCILIATION IT DID NOT PERFORM IS WORSE THAN ONE
      *    THAT PRINTS NONE.
       01  WS-REJECT-CTL-LINE.
           05  FILLER                PIC X(06) VALUE SPACES.
           05  FILLER                PIC X(17) VALUE
               'ACCTPOST REPORTED'.
           05  FILLER                PIC X(31) VALUE SPACES.
           05  WS-RJ-CTL-NUM         PIC ZZZZ,ZZZ,ZZ9.
           05  WS-RJ-CTL-TXT REDEFINES WS-RJ-CTL-NUM PIC X(12).
           05  FILLER                PIC X(66) VALUE SPACES.

       01  WS-BALANCE-LINE.
           05  FILLER                PIC X(02) VALUE SPACES.
           05  WS-BAL-STATUS         PIC X(20) VALUE SPACES.
           05  FILLER                PIC X(110) VALUE SPACES.

       PROCEDURE DIVISION.

       MAIN-PARA.
           PERFORM SET-RUN-CONTEXT
           PERFORM OPEN-FILES
           PERFORM READ-ACCOUNT
           PERFORM UNTIL RPTIN-EOF
               IF ACCT-CURRENCY NOT = WS-CURRENT-CCY
                   PERFORM CLOSE-CURRENCY
                   PERFORM START-CURRENCY
               END-IF
               PERFORM WRITE-DETAIL
               PERFORM READ-ACCOUNT
           END-PERFORM

      *    THE LAST CURRENCY HAS NO RECORD AFTER IT TO TRIGGER ITS
      *    BREAK.  A CONTROL-BREAK REPORT THAT FORGETS THIS DROPS
      *    EXACTLY ONE SUBTOTAL - THE LAST - AND NOBODY NOTICES UNTIL
      *    THE FIGURES ARE ADDED UP BY HAND.
           PERFORM CLOSE-CURRENCY

           PERFORM READ-ALL-REJECTS
           PERFORM WRITE-CURRENCY-RECAP
           PERFORM WRITE-REJECT-RECAP
           PERFORM WRITE-CONTROL-TOTALS

           PERFORM CLOSE-FILES
           MOVE WS-RETURN-CODE TO RETURN-CODE
           STOP RUN.

       SET-RUN-CONTEXT.
           ACCEPT WS-BUS-DATE FROM ENVIRONMENT 'EODREPT_BUS_DATE'
           ACCEPT WS-RUN-TS   FROM ENVIRONMENT 'EODREPT_RUN_TS'
           ACCEPT WS-CTL-REJECTED-TXT
               FROM ENVIRONMENT 'EODREPT_CTL_REJECTED'
           IF WS-CTL-REJECTED-TXT NOT = SPACES
               AND WS-CTL-REJECTED-TXT NOT = LOW-VALUES
               COMPUTE WS-CTL-REJECTED =
                   FUNCTION NUMVAL(WS-CTL-REJECTED-TXT)
               SET CTL-SUPPLIED TO TRUE
           END-IF
           IF WS-BUS-DATE = SPACES OR LOW-VALUES
               MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-DATA
               MOVE WS-CD-DATE TO WS-BUS-DATE
           END-IF
           IF WS-RUN-TS = SPACES OR LOW-VALUES
               MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-DATA
               STRING WS-CD-DATE WS-CD-TIME
                   DELIMITED BY SIZE INTO WS-RUN-TS
           END-IF

           MOVE WS-BUS-DATE TO WS-DATE-IN
           PERFORM EDIT-DATE
           MOVE WS-DATE-ED TO WS-H2-BUS-DATE

           MOVE WS-RUN-TS TO WS-TS-IN
           MOVE WS-TI-DATE TO WS-DATE-IN
           PERFORM EDIT-DATE
           MOVE WS-DATE-ED TO WS-TE-DATE
           MOVE WS-TI-HH TO WS-TE-HH
           MOVE WS-TI-MI TO WS-TE-MI
           MOVE WS-TI-SS TO WS-TE-SS
           MOVE WS-TS-ED TO WS-H2-RUN-TS.

       EDIT-DATE.
           MOVE WS-DI-YYYY TO WS-DE-YYYY
           MOVE WS-DI-MM   TO WS-DE-MM
           MOVE WS-DI-DD   TO WS-DE-DD.

       OPEN-FILES.
           OPEN INPUT  ACCT-REPORT-IN
           OPEN INPUT  REJECT-IN
           OPEN OUTPUT PRINT-FILE
           IF WS-RPTIN-STATUS NOT = '00'
               DISPLAY 'EODREPT ABEND OPEN ACCTRPT ' WS-RPTIN-STATUS
               MOVE 12 TO WS-RETURN-CODE
               MOVE WS-RETURN-CODE TO RETURN-CODE
               STOP RUN
           END-IF
      *    A MISSING REJECTS FILE IS NOT AN EMPTY ONE.  ACCTPOST WRITES
      *    IT ON EVERY RUN, SO ITS ABSENCE MEANS THE STEP BEFORE THIS
      *    ONE DID NOT DO WHAT THE JOB LOG CLAIMS.
           IF WS-REJIN-STATUS NOT = '00'
               DISPLAY 'EODREPT ABEND OPEN REJECTS ' WS-REJIN-STATUS
               MOVE 12 TO WS-RETURN-CODE
               MOVE WS-RETURN-CODE TO RETURN-CODE
               STOP RUN
           END-IF.

       CLOSE-FILES.
           CLOSE ACCT-REPORT-IN
           CLOSE REJECT-IN
           CLOSE PRINT-FILE.

       READ-ACCOUNT.
           READ ACCT-REPORT-IN INTO ACCOUNT-RECORD
               AT END
                   SET RPTIN-EOF TO TRUE
           END-READ.

       START-CURRENCY.
           MOVE ACCT-CURRENCY TO WS-CURRENT-CCY
           MOVE ZERO TO WS-CCY-COUNT WS-CCY-BOOKED WS-CCY-AVAIL
           MOVE 'Y' TO WS-PAGE-BREAK-WANTED.

       CLOSE-CURRENCY.
           IF WS-CURRENT-CCY NOT = SPACES
               PERFORM WRITE-CURRENCY-TOTAL
               PERFORM REMEMBER-CURRENCY
               ADD WS-CCY-COUNT TO WS-GRAND-COUNT
           END-IF.

      *    ONE ROW PER CURRENCY, ADDED AS THE CURRENCY CLOSES.  THE
      *    INPUT IS SORTED BY CURRENCY, SO EACH CLOSES EXACTLY ONCE.
       REMEMBER-CURRENCY.
           IF WS-CCY-ENTRIES < WS-CCY-MAX
               ADD 1 TO WS-CCY-ENTRIES
               MOVE WS-CURRENT-CCY TO WS-CE-CCY(WS-CCY-ENTRIES)
               MOVE WS-CCY-COUNT   TO WS-CE-COUNT(WS-CCY-ENTRIES)
               MOVE WS-CCY-BOOKED  TO WS-CE-BOOKED(WS-CCY-ENTRIES)
               MOVE WS-CCY-AVAIL   TO WS-CE-AVAIL(WS-CCY-ENTRIES)
           ELSE
               DISPLAY 'EODREPT ABEND CURRENCY TABLE FULL'
               MOVE 12 TO WS-RETURN-CODE
           END-IF.

       READ-ALL-REJECTS.
           PERFORM UNTIL REJIN-EOF
               READ REJECT-IN INTO REJECT-RECORD
                   AT END
                       SET REJIN-EOF TO TRUE
                   NOT AT END
                       PERFORM COUNT-ONE-REJECT
               END-READ
           END-PERFORM.

       COUNT-ONE-REJECT.
           ADD 1 TO WS-RJ-TOTAL
           MOVE 'N' TO WS-FOUND
           PERFORM VARYING WS-IX FROM 1 BY 1
               UNTIL WS-IX > WS-RJ-ENTRIES OR WS-FOUND = 'Y'
               IF WS-RE-CODE(WS-IX) = REJ-REASON-CODE
                   ADD 1 TO WS-RE-COUNT(WS-IX)
                   MOVE 'Y' TO WS-FOUND
               END-IF
           END-PERFORM
           IF WS-FOUND = 'N'
               IF WS-RJ-ENTRIES < WS-RJ-MAX
                   ADD 1 TO WS-RJ-ENTRIES
                   MOVE REJ-REASON-CODE TO WS-RE-CODE(WS-RJ-ENTRIES)
                   MOVE REJ-REASON-TEXT TO WS-RE-TEXT(WS-RJ-ENTRIES)
                   MOVE 1 TO WS-RE-COUNT(WS-RJ-ENTRIES)
               ELSE
                   DISPLAY 'EODREPT ABEND REJECT TABLE FULL'
                   MOVE 12 TO WS-RETURN-CODE
               END-IF
           END-IF.

       WRITE-CURRENCY-RECAP.
           PERFORM WRITE-PAGE-HEADER
           WRITE PRINT-LINE FROM WS-RECAP-HEAD AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-RECAP-COLS AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-RECAP-RULE AFTER ADVANCING 1 LINE
           PERFORM VARYING WS-IX FROM 1 BY 1
               UNTIL WS-IX > WS-CCY-ENTRIES
               MOVE WS-CE-CCY(WS-IX)    TO WS-RR-CCY
               MOVE WS-CE-COUNT(WS-IX)  TO WS-RR-COUNT
               MOVE WS-CE-BOOKED(WS-IX) TO WS-RR-BOOKED
               MOVE WS-CE-AVAIL(WS-IX)  TO WS-RR-AVAIL
               WRITE PRINT-LINE FROM WS-RECAP-ROW
                   AFTER ADVANCING 1 LINE
           END-PERFORM
           WRITE PRINT-LINE FROM WS-RECAP-RULE AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           MOVE WS-GRAND-COUNT TO WS-GT-COUNT
           WRITE PRINT-LINE FROM WS-GRAND-LINE AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-NO-XCCY-LINE
               AFTER ADVANCING 1 LINE.

       WRITE-REJECT-RECAP.
           PERFORM WRITE-PAGE-HEADER
           WRITE PRINT-LINE FROM WS-REJECT-HEAD AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-REJECT-COLS AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-REJECT-RULE AFTER ADVANCING 1 LINE
           PERFORM VARYING WS-IX FROM 1 BY 1
               UNTIL WS-IX > WS-RJ-ENTRIES
               MOVE WS-RE-CODE(WS-IX)  TO WS-RJ-ROW-CODE
               MOVE WS-RE-TEXT(WS-IX)  TO WS-RJ-ROW-TEXT
               MOVE WS-RE-COUNT(WS-IX) TO WS-RJ-ROW-COUNT
               WRITE PRINT-LINE FROM WS-REJECT-ROW
                   AFTER ADVANCING 1 LINE
           END-PERFORM
           WRITE PRINT-LINE FROM WS-REJECT-RULE AFTER ADVANCING 1 LINE
           MOVE WS-RJ-TOTAL TO WS-RJ-TOTAL-ED
           WRITE PRINT-LINE FROM WS-REJECT-TOTAL-LINE
               AFTER ADVANCING 1 LINE

           IF CTL-SUPPLIED
               MOVE WS-CTL-REJECTED TO WS-RJ-CTL-NUM
           ELSE
               MOVE 'NOT SUPPLIED' TO WS-RJ-CTL-TXT
           END-IF
           WRITE PRINT-LINE FROM WS-REJECT-CTL-LINE
               AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE

           IF NOT CTL-SUPPLIED
               MOVE '*** NOT RECONCILED' TO WS-BAL-STATUS
           ELSE
               IF WS-CTL-REJECTED = WS-RJ-TOTAL
                   MOVE '*** IN BALANCE' TO WS-BAL-STATUS
               ELSE
                   MOVE '*** OUT OF BALANCE' TO WS-BAL-STATUS
                   MOVE 12 TO WS-RETURN-CODE
               END-IF
           END-IF
           WRITE PRINT-LINE FROM WS-BALANCE-LINE AFTER ADVANCING 1 LINE.

      *    THE SAME GREPPABLE SHAPE ACCTPOST USES, SO THE CYCLE RUNNER
      *    READS BOTH JOB LOGS WITH ONE PARSER.
       WRITE-CONTROL-TOTALS.
           MOVE WS-GRAND-COUNT TO WS-COUNT-ED
           DISPLAY 'EODREPT CTL ACCOUNTS    ' WS-COUNT-ED
           MOVE WS-CCY-ENTRIES TO WS-COUNT-ED
           DISPLAY 'EODREPT CTL CURRENCIES  ' WS-COUNT-ED
           MOVE WS-RJ-TOTAL TO WS-COUNT-ED
           DISPLAY 'EODREPT CTL REJECTED    ' WS-COUNT-ED
           MOVE WS-PAGE-NO TO WS-COUNT-ED
           DISPLAY 'EODREPT CTL PAGES       ' WS-COUNT-ED
           IF WS-RETURN-CODE = ZERO
               DISPLAY 'EODREPT CTL BALANCED'
           ELSE
               DISPLAY 'EODREPT CTL OUT-OF-BALANCE'
           END-IF.

      *    THE SUBTOTAL AND ITS BLANK LINE NEED ROOM.  WITHOUT THIS A
      *    CURRENCY THAT EXACTLY FILLS ITS LAST PAGE PRINTS ITS TOTAL
      *    TWO LINES BELOW THE BOTTOM OF THE PAGE.
       WRITE-CURRENCY-TOTAL.
           COMPUTE WS-LINES-NEEDED = WS-BODY-LINES + 2
           IF WS-LINES-NEEDED > WS-BODY-LIMIT
               PERFORM WRITE-PAGE-HEADER
           END-IF
           MOVE WS-CURRENT-CCY TO WS-CT-CCY
           MOVE WS-CCY-COUNT   TO WS-CT-COUNT
           MOVE WS-CCY-BOOKED  TO WS-CT-BOOKED
           MOVE WS-CCY-AVAIL   TO WS-CT-AVAIL
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-CCY-TOTAL-LINE
               AFTER ADVANCING 1 LINE
           ADD 2 TO WS-BODY-LINES.

       WRITE-DETAIL.
           IF PAGE-BREAK-WANTED OR WS-BODY-LINES >= WS-BODY-LIMIT
               PERFORM WRITE-PAGE-HEADER
           END-IF

           MOVE ACCT-REF           TO WS-DT-REF
           MOVE ACCT-CUST-REF      TO WS-DT-CUST
           MOVE ACCT-TYPE          TO WS-DT-TYPE
           MOVE ACCT-STATUS        TO WS-DT-STATUS
           MOVE ACCT-CURRENCY      TO WS-DT-CCY
           MOVE ACCT-BOOKED-BAL    TO WS-DT-BOOKED
           MOVE ACCT-AVAIL-BAL     TO WS-DT-AVAIL
           MOVE ACCT-LAST-MOVE-DATE TO WS-DATE-IN
           PERFORM EDIT-DATE
           MOVE WS-DATE-ED         TO WS-DT-LAST-MOVE

           WRITE PRINT-LINE FROM WS-DETAIL AFTER ADVANCING 1 LINE
           ADD 1 TO WS-BODY-LINES

           ADD 1 TO WS-CCY-COUNT
           ADD ACCT-BOOKED-BAL TO WS-CCY-BOOKED
           ADD ACCT-AVAIL-BAL  TO WS-CCY-AVAIL.

       WRITE-PAGE-HEADER.
           ADD 1 TO WS-PAGE-NO
           MOVE WS-PAGE-NO TO WS-H1-PAGE
           WRITE PRINT-LINE FROM WS-HEAD-1 AFTER ADVANCING PAGE
           WRITE PRINT-LINE FROM WS-HEAD-2 AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-HEAD-3 AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-HEAD-4 AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           MOVE ZERO TO WS-BODY-LINES
           MOVE 'N' TO WS-PAGE-BREAK-WANTED.
