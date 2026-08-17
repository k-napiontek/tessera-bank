#!/bin/sh
#
# Compile every copybook, proving cobc accepts the layouts.
#
# -std=ibm rather than -std=cobol85: COMP-3 is an IBM extension. Strict ANSI COBOL-85 spells packed
# decimal PACKED-DECIMAL and rejects COMP-3 outright, which this harness discovered the first time it
# ran. Both spellings produce identical bytes, and every banking COBOL program in existence writes
# COMP-3, so the copybooks keep COMP-3 and the compiler is told which dialect that is.
#
# -fsyntax-only because there is nothing to run: the harness declares the records and stops.

set -eu

cd "$(dirname "$0")/../.."

echo "GnuCOBOL: $(cobc --version | head -1)"
echo

cobc -fsyntax-only -std=ibm -Wall -I mainframe/copybook mainframe/copybook/CPYCHK.CBL

echo "OK    ACCTREC, MOVEREC and REJREC compile in IBM COBOL-85 fixed format"
