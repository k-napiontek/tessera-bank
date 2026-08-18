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

           SELECT PRINT-FILE      ASSIGN TO 'EODREPT.TXT'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-PRINT-STATUS.

       DATA DIVISION.
       FILE SECTION.

       FD  ACCT-REPORT-IN
           RECORD CONTAINS 100 CHARACTERS.
       01  REPORT-IN-RECORD        PIC X(100).

      *    NO RECORD CLAUSE ON THE PRINT FILE.  LINE SEQUENTIAL TAKES
      *    ITS LENGTH FROM THE RECORD AND COBC WARNS THAT THE CLAUSE IS
      *    IGNORED; THE 132 COLUMNS LIVE IN PIC X(132) INSTEAD.
       FD  PRINT-FILE.
       01  PRINT-LINE              PIC X(132).

       WORKING-STORAGE SECTION.

       COPY ACCTREC.

       01  WS-FILE-STATUS.
           05  WS-RPTIN-STATUS       PIC X(02) VALUE SPACES.
           05  WS-PRINT-STATUS       PIC X(02) VALUE SPACES.

       01  WS-FLAGS.
           05  WS-RPTIN-EOF          PIC X(01) VALUE 'N'.
               88  RPTIN-EOF                   VALUE 'Y'.

      *    PAGE CONTROL.  SIXTY LINES TO A PAGE, SIX OF THEM HEADER,
      *    LEAVING FIFTY-FOUR FOR THE BODY.  AN OFF-BY-ONE HERE PRINTS
      *    A REPORT THAT LOOKS ENTIRELY NORMAL UNTIL SOMEONE COUNTS.
       01  WS-PAGE-NO                PIC 9(04) VALUE ZERO.
       01  WS-BODY-LINES             PIC 9(03) VALUE ZERO.
       01  WS-BODY-LIMIT             PIC 9(03) VALUE 54.

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

       PROCEDURE DIVISION.

       MAIN-PARA.
           PERFORM SET-RUN-CONTEXT
           PERFORM OPEN-FILES
           PERFORM READ-ACCOUNT
           PERFORM UNTIL RPTIN-EOF
               PERFORM WRITE-DETAIL
               PERFORM READ-ACCOUNT
           END-PERFORM
           PERFORM CLOSE-FILES
           MOVE WS-RETURN-CODE TO RETURN-CODE
           STOP RUN.

       SET-RUN-CONTEXT.
           ACCEPT WS-BUS-DATE FROM ENVIRONMENT 'EODREPT_BUS_DATE'
           ACCEPT WS-RUN-TS   FROM ENVIRONMENT 'EODREPT_RUN_TS'
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
           OPEN OUTPUT PRINT-FILE
           IF WS-RPTIN-STATUS NOT = '00'
               DISPLAY 'EODREPT ABEND OPEN ACCTRPT ' WS-RPTIN-STATUS
               MOVE 12 TO WS-RETURN-CODE
               MOVE WS-RETURN-CODE TO RETURN-CODE
               STOP RUN
           END-IF.

       CLOSE-FILES.
           CLOSE ACCT-REPORT-IN
           CLOSE PRINT-FILE.

       READ-ACCOUNT.
           READ ACCT-REPORT-IN INTO ACCOUNT-RECORD
               AT END
                   SET RPTIN-EOF TO TRUE
           END-READ.

       WRITE-DETAIL.
           IF WS-PAGE-NO = ZERO OR WS-BODY-LINES >= WS-BODY-LIMIT
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
           ADD 1 TO WS-BODY-LINES.

       WRITE-PAGE-HEADER.
           ADD 1 TO WS-PAGE-NO
           MOVE WS-PAGE-NO TO WS-H1-PAGE
           WRITE PRINT-LINE FROM WS-HEAD-1 AFTER ADVANCING PAGE
           WRITE PRINT-LINE FROM WS-HEAD-2 AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-HEAD-3 AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-HEAD-4 AFTER ADVANCING 1 LINE
           WRITE PRINT-LINE FROM WS-BLANK-LINE AFTER ADVANCING 1 LINE
           MOVE ZERO TO WS-BODY-LINES.
