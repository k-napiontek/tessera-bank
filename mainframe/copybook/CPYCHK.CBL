      *================================================================*
      * CPYCHK - COPYBOOK COMPILE HARNESS                              *
      *                                                                *
      * NOT AN APPLICATION PROGRAM.  ACCTPOST IS WP-04, EODREPT IS     *
      * WP-05.                                                         *
      *                                                                *
      * THIS EXISTS ONLY TO MAKE THE COMPILER READ EVERY COPYBOOK.  THE*
      * COLUMN ARITHMETIC IN contracts/check-copybook-offsets.py PROVES*
      * THE OFFSETS ADD UP; IT CANNOT PROVE THAT cobc ACCEPTS THE      *
      * PICTURE                                                        *
      * CLAUSES, THE LEVEL NUMBERS OR THE FIXED-FORMAT COLUMNS.  A     *
      * LAYOUT                                                         *
      * THAT ADDS UP AND DOES NOT COMPILE IS STILL BROKEN.             *
      *                                                                *
      * DIALECT: -std=ibm, NOT -std=cobol85.  COMP-3 IS AN IBM         *
      * EXTENSION;                                                     *
      * STRICT ANSI COBOL-85 SPELLS IT PACKED-DECIMAL AND REJECTS      *
      * COMP-3                                                         *
      * OUTRIGHT.  EVERY BANKING COBOL PROGRAM EVER WRITTEN SAYS       *
      * COMP-3,                                                        *
      * SO THE COPYBOOKS SAY COMP-3 AND THE COMPILER IS TOLD WHICH     *
      * DIALECT THAT IS.  THE TWO SPELLINGS PRODUCE IDENTICAL BYTES.   *
      *                                                                *
      * COMPILE WITH:                                                  *
      *   sh mainframe/copybook/compile-check.sh                       *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CPYCHK.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

       COPY ACCTREC.

       COPY MOVEREC.

       COPY REJREC.

       PROCEDURE DIVISION.
       MAIN-PARA.
           STOP RUN.
