      *================================================================*
      * ACCTPOST - APPLY A DAY OF MOVEMENTS TO THE ACCOUNT MASTER      *
      *                                                                *
      * THE BALANCED-LINE MATCH-MERGE.  ONE SEQUENTIAL PASS OVER TWO   *
      * SORTED FILES, PRODUCING A NEW MASTER AND A REJECTS FILE.       *
      *                                                                *
      * THE MASTER IS NOT READ INTO MEMORY AND NEVER WILL BE.  MATCH-  *
      * MERGE EXISTS BECAUSE THE MASTER DOES NOT FIT.  A VERSION THAT  *
      * LOADS IT INTO A TABLE WOULD PASS EVERY TEST IN THIS PACKAGE AND*
      * DESTROY ITS ENTIRE PURPOSE.                                    *
      *                                                                *
      * INPUT   ACCTMAST.DAT  ACCTREC, 100 BYTES, ASCENDING BY ACCT-REF*
      *         MOVEMENT.DAT  MOVEREC, 120 BYTES, ASCENDING BY MOV-    *
      * ACCT-REF                                                       *
      * OUTPUT  ACCTNEW.DAT   ACCTREC, THE NEW MASTER                  *
      *         REJECTS.DAT   REJREC, 200 BYTES                        *
      *                                                                *
      * REJECTION IS A BUSINESS OUTCOME, NOT AN ABEND.  A MOVEMENT THAT*
      * CANNOT BE APPLIED IS WRITTEN TO THE REJECTS FILE AND THE RUN   *
      * CONTINUES.  CONTROL TOTALS MUST BALANCE: READ = APPLIED +      *
      * REJECTED.                                                      *
      *                                                                *
      * COMPILE:                                                       *
      *   cobc -x -std=ibm -Wall -I mainframe/copybook ACCTPOST.CBL    *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. ACCTPOST.
       AUTHOR. TESSERA BANK CORE BANKING.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.

      *    ORGANIZATION IS SEQUENTIAL, NEVER LINE SEQUENTIAL.  A
      *    COMP-3 AMOUNT CAN CONTAIN X'0A' OR X'0D'.  LINE
      *    SEQUENTIAL WOULD CORRUPT EVERY PACKED FIELD AND WOULD
      *    LOOK AS THOUGH IT HAD WORKED.

           SELECT ACCT-MASTER-IN  ASSIGN TO 'ACCTMAST.DAT'
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-MASTER-STATUS.

           SELECT MOVEMENT-IN     ASSIGN TO 'MOVEMENT.DAT'
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-MOVE-STATUS.

           SELECT ACCT-MASTER-OUT ASSIGN TO 'ACCTNEW.DAT'
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-NEWMAST-STATUS.

           SELECT REJECT-OUT      ASSIGN TO 'REJECTS.DAT'
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-REJECT-STATUS.

       DATA DIVISION.
       FILE SECTION.

       FD  ACCT-MASTER-IN
           RECORD CONTAINS 100 CHARACTERS.
       COPY ACCTREC.

       FD  MOVEMENT-IN
           RECORD CONTAINS 120 CHARACTERS.
       COPY MOVEREC.

       FD  ACCT-MASTER-OUT
           RECORD CONTAINS 100 CHARACTERS.
       01  NEW-ACCOUNT-RECORD      PIC X(100).

       FD  REJECT-OUT
           RECORD CONTAINS 200 CHARACTERS.
       COPY REJREC.

       WORKING-STORAGE SECTION.

       01  WS-FILE-STATUS.
           05  WS-MASTER-STATUS      PIC X(02) VALUE SPACES.
           05  WS-MOVE-STATUS        PIC X(02) VALUE SPACES.
           05  WS-NEWMAST-STATUS     PIC X(02) VALUE SPACES.
           05  WS-REJECT-STATUS      PIC X(02) VALUE SPACES.

       01  WS-FLAGS.
           05  WS-MASTER-EOF         PIC X(01) VALUE 'N'.
               88  MASTER-EOF                  VALUE 'Y'.
           05  WS-MOVE-EOF           PIC X(01) VALUE 'N'.
               88  MOVE-EOF                    VALUE 'Y'.

      *    THE BALANCED LINE.  AT EACH STEP THE LOWER OF THE TWO
      *    KEYS IS TAKEN; HIGH-VALUES STANDS IN FOR A FILE THAT HAS
      *    REACHED END, SO IT SORTS LAST AND THE OTHER FILE DRAINS.
       01  WS-CURRENT-KEY            PIC X(16) VALUE SPACES.
       01  WS-MASTER-KEY             PIC X(16) VALUE SPACES.
       01  WS-MOVE-KEY               PIC X(16) VALUE SPACES.
       01  WS-MASTER-HELD            PIC X(01) VALUE 'N'.
           88  MASTER-HELD                     VALUE 'Y'.

       01  WS-HELD-ACCOUNT           PIC X(100) VALUE SPACES.

       01  WS-TOTALS.
           05  WS-MOVE-READ          PIC 9(09) COMP-3 VALUE ZERO.
           05  WS-MOVE-APPLIED       PIC 9(09) COMP-3 VALUE ZERO.
           05  WS-MOVE-REJECTED      PIC 9(09) COMP-3 VALUE ZERO.
           05  WS-MASTER-READ        PIC 9(09) COMP-3 VALUE ZERO.
           05  WS-MASTER-WRITTEN     PIC 9(09) COMP-3 VALUE ZERO.
           05  WS-VALUE-MOVED        PIC S9(15) COMP-3 VALUE ZERO.

       01  WS-EDIT.
           05  WS-COUNT-ED           PIC ZZZ,ZZZ,ZZ9.
           05  WS-VALUE-ED           PIC -Z(13)9.99.

       01  WS-RETURN-CODE            PIC 9(02) VALUE ZERO.

       PROCEDURE DIVISION.

       MAIN-PARA.
           PERFORM OPEN-FILES
           PERFORM PRIME-READS
           PERFORM MATCH-MERGE
           PERFORM WRITE-CONTROL-TOTALS
           PERFORM CLOSE-FILES
           MOVE WS-RETURN-CODE TO RETURN-CODE
           STOP RUN.

       OPEN-FILES.
           OPEN INPUT  ACCT-MASTER-IN
           OPEN INPUT  MOVEMENT-IN
           OPEN OUTPUT ACCT-MASTER-OUT
           OPEN OUTPUT REJECT-OUT
           IF WS-MASTER-STATUS NOT = '00'
               DISPLAY 'ACCTPOST ABEND OPEN ACCTMAST ' WS-MASTER-STATUS
               MOVE 12 TO WS-RETURN-CODE
               PERFORM CLOSE-FILES
               MOVE WS-RETURN-CODE TO RETURN-CODE
               STOP RUN
           END-IF
           IF WS-MOVE-STATUS NOT = '00'
               DISPLAY 'ACCTPOST ABEND OPEN MOVEMENT ' WS-MOVE-STATUS
               MOVE 12 TO WS-RETURN-CODE
               PERFORM CLOSE-FILES
               MOVE WS-RETURN-CODE TO RETURN-CODE
               STOP RUN
           END-IF.

       CLOSE-FILES.
           CLOSE ACCT-MASTER-IN
           CLOSE MOVEMENT-IN
           CLOSE ACCT-MASTER-OUT
           CLOSE REJECT-OUT.

       PRIME-READS.
           PERFORM READ-MASTER
           PERFORM READ-MOVEMENT.

       READ-MASTER.
           READ ACCT-MASTER-IN
               AT END
                   SET MASTER-EOF TO TRUE
                   MOVE HIGH-VALUES TO WS-MASTER-KEY
               NOT AT END
                   ADD 1 TO WS-MASTER-READ
                   MOVE ACCT-REF TO WS-MASTER-KEY
           END-READ.

       READ-MOVEMENT.
           READ MOVEMENT-IN
               AT END
                   SET MOVE-EOF TO TRUE
                   MOVE HIGH-VALUES TO WS-MOVE-KEY
               NOT AT END
                   ADD 1 TO WS-MOVE-READ
                   MOVE MOV-ACCT-REF TO WS-MOVE-KEY
           END-READ.

      *    THE PASS ITSELF.  TAKE THE LOWER KEY, HOLD THE MASTER
      *    RECORD IF IT MATCHES, DRAIN EVERY MOVEMENT FOR THAT KEY,
      *    THEN WRITE THE MASTER RECORD ONCE.
       MATCH-MERGE.
           PERFORM UNTIL MASTER-EOF AND MOVE-EOF
               IF WS-MASTER-KEY <= WS-MOVE-KEY
                   MOVE WS-MASTER-KEY TO WS-CURRENT-KEY
               ELSE
                   MOVE WS-MOVE-KEY TO WS-CURRENT-KEY
               END-IF

               MOVE 'N' TO WS-MASTER-HELD
               IF WS-MASTER-KEY = WS-CURRENT-KEY AND NOT MASTER-EOF
                   MOVE ACCOUNT-RECORD TO WS-HELD-ACCOUNT
                   SET MASTER-HELD TO TRUE
                   PERFORM READ-MASTER
               END-IF

               PERFORM APPLY-MOVEMENTS-FOR-KEY
                   UNTIL WS-MOVE-KEY NOT = WS-CURRENT-KEY

               IF MASTER-HELD
                   MOVE WS-HELD-ACCOUNT TO NEW-ACCOUNT-RECORD
                   WRITE NEW-ACCOUNT-RECORD
                   ADD 1 TO WS-MASTER-WRITTEN
               END-IF
           END-PERFORM.

       APPLY-MOVEMENTS-FOR-KEY.
           PERFORM READ-MOVEMENT.

       WRITE-CONTROL-TOTALS.
           MOVE WS-MASTER-READ TO WS-COUNT-ED
           DISPLAY 'ACCTPOST CTL MASTER-READ    ' WS-COUNT-ED
           MOVE WS-MASTER-WRITTEN TO WS-COUNT-ED
           DISPLAY 'ACCTPOST CTL MASTER-WRITTEN ' WS-COUNT-ED
           MOVE WS-MOVE-READ TO WS-COUNT-ED
           DISPLAY 'ACCTPOST CTL MOVE-READ      ' WS-COUNT-ED
           MOVE WS-MOVE-APPLIED TO WS-COUNT-ED
           DISPLAY 'ACCTPOST CTL MOVE-APPLIED   ' WS-COUNT-ED
           MOVE WS-MOVE-REJECTED TO WS-COUNT-ED
           DISPLAY 'ACCTPOST CTL MOVE-REJECTED  ' WS-COUNT-ED
           MOVE WS-VALUE-MOVED TO WS-VALUE-ED
           DISPLAY 'ACCTPOST CTL VALUE-MOVED    ' WS-VALUE-ED

           IF WS-MOVE-READ NOT = WS-MOVE-APPLIED + WS-MOVE-REJECTED
               DISPLAY 'ACCTPOST CTL OUT-OF-BALANCE'
               MOVE 12 TO WS-RETURN-CODE
           ELSE
               DISPLAY 'ACCTPOST CTL BALANCED'
           END-IF.
