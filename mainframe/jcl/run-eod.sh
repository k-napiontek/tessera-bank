#!/usr/bin/env bash
#
# run-eod.sh - the end-of-day cycle, run locally.
#
# EODCYCLE.JCL is the artefact this reproduces. GnuCOBOL cannot execute JCL, so this script runs the
# same four steps in the same order, checks each step's return code the way JCL checks COND, and
# stops the cycle dead on the first failure. The two must always describe the same graph;
# test-eod-cycle.py fails if they diverge.
#
#   STEP010  SORT      the movements as delivered      -> account-reference sequence
#   STEP020  ACCTPOST  sorted movements + old master   -> new master, rejects
#   STEP030  SORT      the new master                  -> currency then reference sequence
#   STEP040  EODREPT   report-sequenced master + rejects -> the printed report
#
# Idempotent by construction: the work directory is seeded from the input master on every run, and
# the run timestamp is derived from the business date rather than the clock, so two runs over the
# same inputs produce byte-identical outputs. Applying the same movement file twice is refused
# outright - see the marker file at the end.
#
#   bash mainframe/jcl/run-eod.sh --business-date 20260818
#   bash mainframe/jcl/run-eod.sh --steps            # the job graph, one step per line
#
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COPYBOOK="$REPO/mainframe/copybook"
SORTREC="$REPO/mainframe/jcl/sortrec.py"

# The job graph. EODCYCLE.JCL declares the same steps, in this order, running these programs.
STEP_GRAPH="STEP010:SORT STEP020:ACCTPOST STEP030:SORT STEP040:EODREPT"

BUSINESS_DATE="$(date +%Y%m%d)"
MASTER_IN="$REPO/mainframe/data/out/ACCTMAST.DAT"
MOVEMENT_IN="$REPO/mainframe/data/out/MOVEMENT.DAT"
WORK_ROOT="$REPO/mainframe/data/out/eod"
RUN_TS=""
RERUN="no"

usage() {
    sed -n '3,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --business-date) BUSINESS_DATE="$2"; shift 2 ;;
        --master)        MASTER_IN="$2"; shift 2 ;;
        --movements)     MOVEMENT_IN="$2"; shift 2 ;;
        --work)          WORK_ROOT="$2"; shift 2 ;;
        --run-ts)        RUN_TS="$2"; shift 2 ;;
        --rerun)         RERUN="yes"; shift ;;
        --steps)         printf '%s\n' $STEP_GRAPH | tr ':' ' '; exit 0 ;;
        -h|--help)       usage; exit 0 ;;
        *) echo "EOD ABEND SETUP unknown argument $1" >&2; exit 12 ;;
    esac
done

# The timestamp a batch job stamps on its output belongs to the job, not to the machine. Deriving it
# from the business date is what makes a rerun byte-identical to the first run.
[ -n "$RUN_TS" ] || RUN_TS="${BUSINESS_DATE}030000"

WORK="$WORK_ROOT/$BUSINESS_DATE"
MARKER="$WORK/MOVEMENT.APPLIED"

abend() {
    echo "EOD ABEND $1" >&2
    exit "$2"
}

step_banner() {
    echo
    echo "-- $1 $2  $3"
}

# JCL runs a step only when the ones before it ended clean. This is that, in shell.
check_rc() {
    local step="$1" rc="$2"
    echo "   $step RC=$rc"
    [ "$rc" -eq 0 ] || abend "$step RC=$rc  the cycle stopped, no later step ran" "$rc"
}

# ---------------------------------------------------------------------------------------------
# Setup. Not part of the job graph: on z/OS the load modules already sit in a load library, and
# the datasets are catalogued. Locally they have to be compiled and staged first.
# ---------------------------------------------------------------------------------------------
echo "EOD CYCLE  business date $BUSINESS_DATE  run $RUN_TS"
echo "   master     $MASTER_IN"
echo "   movements  $MOVEMENT_IN"
echo "   work       $WORK"

[ -f "$MASTER_IN" ]   || abend "SETUP master not found: $MASTER_IN" 12
[ -f "$MOVEMENT_IN" ] || abend "SETUP movement file not found: $MOVEMENT_IN" 12

# The guard against posting a day twice. A cycle that applies the same movement file to an already
# updated master doubles every posting in the bank, and "the operator would notice" is not a
# control. The checksum is what identifies the file: a re-presented, corrected file for the same
# business date is normal operations and must not be blocked, while the identical file is the one
# thing that must never be applied again by accident.
if [ -f "$MARKER" ] && [ "$RERUN" = "no" ]; then
    APPLIED_SHA="$(awk '/^sha256/ {print $2}' "$MARKER")"
    CURRENT_SHA="$(shasum -a 256 "$MOVEMENT_IN" | awk '{print $1}')"
    if [ "$APPLIED_SHA" = "$CURRENT_SHA" ]; then
        echo
        cat "$MARKER"
        abend "SETUP this movement file was already applied for $BUSINESS_DATE
   pass --rerun only if you intend to apply it again" 8
    fi
fi

BIN="$WORK_ROOT/bin"
mkdir -p "$WORK" "$BIN" || abend "SETUP cannot create $WORK" 12

for program in ACCTPOST EODREPT; do
    cobc -x -std=ibm -Wall -I "$COPYBOOK" -o "$BIN/$program" \
        "$REPO/mainframe/cobol/$program.CBL" \
        || abend "SETUP $program did not compile" 12
done
echo "   compiled   ACCTPOST, EODREPT"

# Staged under the names the programs expect. ACCTPOST reads ACCTMAST.DAT and MOVEMENT.DAT, so the
# delivered movement file arrives as MOVEMENT.IN and STEP010 writes MOVEMENT.DAT from it.
cp "$MASTER_IN"   "$WORK/ACCTMAST.DAT" || abend "SETUP cannot stage the master" 12
cp "$MOVEMENT_IN" "$WORK/MOVEMENT.IN"  || abend "SETUP cannot stage the movements" 12
rm -f "$WORK/MOVEMENT.DAT" "$WORK/ACCTNEW.DAT" "$WORK/REJECTS.DAT" \
      "$WORK/ACCTRPT.DAT" "$WORK/EODREPT.TXT" "$MARKER"

cd "$WORK" || abend "SETUP cannot enter $WORK" 12

# ---------------------------------------------------------------------------------------------
# STEP010 - sort the movements into account-reference sequence.
# MOV-ACCT-REF is bytes 22 to 38 of MOVEREC: a 20-byte transfer reference and a 2-digit leg first.
# ---------------------------------------------------------------------------------------------
step_banner STEP010 SORT "movements into account-reference sequence"
python3 "$SORTREC" --record-length 120 --key 22:38 MOVEMENT.IN MOVEMENT.DAT
check_rc STEP010 $?

# ---------------------------------------------------------------------------------------------
# STEP020 - apply the movements to the account master.
# ---------------------------------------------------------------------------------------------
step_banner STEP020 ACCTPOST "apply the day to the account master"
ACCTPOST_RUN_TS="$RUN_TS" "$BIN/ACCTPOST" > ACCTPOST.LOG 2>&1
ACCTPOST_RC=$?
cat ACCTPOST.LOG
check_rc STEP020 $ACCTPOST_RC

# What ACCTPOST counted, handed to the report so it can reconcile against it. The count is printed
# edited, with thousands separators, so the commas and padding come off here.
REJECTED="$(awk '/ACCTPOST CTL MOVE-REJECTED/ {gsub(/,/, "", $NF); print $NF + 0}' ACCTPOST.LOG)"
[ -n "$REJECTED" ] || abend "STEP020 wrote no MOVE-REJECTED control total" 12

# ---------------------------------------------------------------------------------------------
# STEP030 - sort the new master into report sequence.
# ACCT-CURRENCY is bytes 37 to 40 of ACCTREC, ACCT-REF is bytes 0 to 16. The report control-breaks
# on currency, which requires the file to arrive in currency order; a report program that sorts for
# itself is a report program that holds the master.
# ---------------------------------------------------------------------------------------------
step_banner STEP030 SORT "new master into currency then reference sequence"
python3 "$SORTREC" --record-length 100 --key 37:40 --key 0:16 ACCTNEW.DAT ACCTRPT.DAT
check_rc STEP030 $?

# ---------------------------------------------------------------------------------------------
# STEP040 - the end-of-day report.
# ---------------------------------------------------------------------------------------------
step_banner STEP040 EODREPT "the end-of-day account report"
EODREPT_BUS_DATE="$BUSINESS_DATE" EODREPT_RUN_TS="$RUN_TS" \
    EODREPT_CTL_REJECTED="$REJECTED" "$BIN/EODREPT" > EODREPT.LOG 2>&1
EODREPT_RC=$?
cat EODREPT.LOG
check_rc STEP040 $EODREPT_RC

# ---------------------------------------------------------------------------------------------
# The cycle ended clean. Record what was applied, so it cannot be applied again by accident. The
# marker is written last: a cycle that failed halfway has applied nothing and must stay rerunnable.
# ---------------------------------------------------------------------------------------------
{
    echo "sha256 $(shasum -a 256 "$WORK/MOVEMENT.IN" | awk '{print $1}')"
    echo "business-date $BUSINESS_DATE"
    echo "run-timestamp $RUN_TS"
    echo "source $MOVEMENT_IN"
} > "$MARKER"

echo
echo "EOD CYCLE COMPLETE  report $WORK/EODREPT.TXT"
exit 0
