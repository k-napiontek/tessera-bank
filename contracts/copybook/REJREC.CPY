      *================================================================*
      * REJREC - REJECTED MOVEMENT RECORD                              *
      *                                                                *
      * LENGTH      200 BYTES, FIXED                                   *
      * ORGANISATION  SEQUENTIAL, IN THE ORDER REJECTIONS WERE DETECTED*
      * SOURCE      docs/architecture/canonical-data-model.md          *
      *                                                                *
      * A MOVEMENT THE OVERNIGHT CYCLE COULD NOT APPLY, WITH ITS       *
      * REASON.                                                        *
      * REJECTION IS A BUSINESS OUTCOME, NOT AN ABEND.                 *
      *                                                                *
      * REJ-MOVEMENT HOLDS THE 120 BYTES OF MOVEREC VERBATIM SO THE    *
      * RECORD CAN BE RE-PRESENTED TO A LATER RUN WITHOUT RE-ENCODING. *
      * REDEFINE IT WITH MOVEREC IN THE PROGRAM THAT READS THIS FILE.  *
      *                                                                *
      * WORKED BY AN OPERATOR IN THE BACK OFFICE.  REJ-REASON-TEXT IS  *
      * OPERATOR-READABLE AND MUST NEVER CARRY PERSONAL DATA.          *
      *================================================================*
       01  REJECT-RECORD.
           05  REJ-MOVEMENT          PIC X(120).
           05  REJ-REASON-CODE       PIC X(04).
           05  REJ-REASON-TEXT       PIC X(40).
           05  REJ-DETECTED-TS       PIC 9(14).
           05  FILLER                PIC X(22).
