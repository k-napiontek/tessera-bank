#!/usr/bin/env bash
#
# One bank day in sequence: the online phase, the cut-off, the overnight cycle, the reconciliation.
#
# **A test fixture, not a component of the bank.** It composes what already exists rather than
# reimplementing any of it, the way soak.sh composes the scripts either side of it: estate-up.sh
# drives the online day, workload-dataset and mainframe/data/generate.py write the same day as
# stratum-0 files, mainframe/jcl/run-eod.sh runs the cycle, and batch/recon compares the master that
# cycle produced against the ledger that fed it. Nothing here changes any of them.
#
#   bash workload/scripts/two-phase-day.sh
#   bash workload/scripts/two-phase-day.sh --customers 40000 --scale 0.002 --date 2026-03-02
#
# **This is the first run in this repository that crosses the online/batch boundary**, and it is the
# run that needs the two halves to agree about the same bank. Three things make them agree, and each
# was a defect until WP-25c:
#
#   * **One opening balance.** The stream carries seeding.Opening's figure and every consumer reads
#     it. Before F-98 the driver funded one number, services/ledger-loader loaded a second and
#     generate.py wrote a third, so a reconciliation between any two of them broke on every account.
#   * **One account set.** --seed-population opens every account the population declares rather than
#     only those the schedule touches, because generate.py writes an ACCTREC for every account the
#     stream opens. Without it every untouched account is a MISSING_IN_LEDGER break, and the real
#     findings are buried under tens of thousands of them.
#   * **One cut-off.** The driver stops at the online-cut-off minute and the stream is bounded by the
#     same minute. A movement file carrying transfers the driver never sent puts them on the master
#     and not in the ledger - and ADR 0015 makes the movement file the reconciliation cut-off, so the
#     difference would be reported as drift rather than as timing.
#   * **One day.** --driver-seed. The emitter derives a per-date seed and the driver uses the run
#     seed, so pointed at the same date they drew *different days* - F-102, found by this script
#     reporting 17 871 actions against the driver's 17 658 on the first run that compared them.
#
# **The run spans two business dates and says which phase belongs to which.** The day contract puts
# overnight-batch at minute 1230 of the business date and 300 of the next, and
# morning-reconciliation at 300 to 420 - so the cycle is date D's work and the reconciliation is
# worked on D+1, before the branches open.
#
# **The population is capped and stratum 0 is why.** The treasury carries one leg of every funding,
# so its balance is the account count times the opening figure, and ACCT-BOOKED-BAL is
# PIC S9(13)V99 COMP-3. At seeding.Opening's figure the master tops out at 99 999 accounts - F-101.
# generate.py refuses past it rather than writing a corrupt record.
#
# **A break is not a failure of this script.** batch/recon exits 0 when it finds breaks: it ran, it
# compared, it disagreed, and that is the control working. A non-zero exit means a phase did not run.
#
# Needs: Docker, a JDK 17, Go, uv and GnuCOBOL.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${TB_TWO_PHASE_OUT:-$ROOT/workload/baselines/two-phase-day}"
WORK="${TMPDIR:-/tmp}/tessera-two-phase"

MODEL="$ROOT/contracts/workload/tessera-day-v1.json"
BUSINESS_DATE=2026-03-02
CUSTOMERS=40000
SCALE=0.002
COMPRESS=720
SEED=42

# Its own container and port, so a two-phase run never lands in the database baseline.sh, soak.sh or
# the signature sweep are holding. TB_KEEP_DATA keeps it up after estate-up.sh tears the processes
# down, which is what lets the reconciliation read the ledger the day was driven against.
DB_CONTAINER=tessera-twophase-db
DB_PORT=5436

usage() { sed -n '3,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --customers) CUSTOMERS="$2"; shift 2 ;;
        --scale)     SCALE="$2"; shift 2 ;;
        --compress)  COMPRESS="$2"; shift 2 ;;
        --date)      BUSINESS_DATE="$2"; shift 2 ;;
        --seed)      SEED="$2"; shift 2 ;;
        --out)       OUT="$2"; shift 2 ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "two-phase-day: unknown argument $1" >&2; exit 2 ;;
    esac
done

command -v cobc >/dev/null || { echo "two-phase-day: GnuCOBOL is not installed - brew install gnucobol" >&2; exit 1; }
command -v uv   >/dev/null || { echo "two-phase-day: uv is not installed" >&2; exit 1; }

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# The instant and the two windows, read out of the day contract rather than restated here. All three
# already exist; this script sequences them and adds nothing.
minute_of() {
    python3 -c "
import json,sys
model=json.load(open('$MODEL'))
for instant in model['calendar']['instants']:
    if instant['id']=='$1':
        print(instant['atMinute']); sys.exit(0)
sys.exit('two-phase-day: the model declares no instant \'$1\'')
"
}
window_of() {
    python3 -c "
import json,sys
model=json.load(open('$MODEL'))
for window in model['calendar']['windows']:
    if window['id']=='$1':
        print(window['startMinute'], window['endMinute']); sys.exit(0)
sys.exit('two-phase-day: the model declares no window \'$1\'')
"
}

CUT_OFF=$(minute_of online-cut-off)
read -r BATCH_FROM BATCH_TO <<<"$(window_of overnight-batch)"
read -r RECON_FROM RECON_TO <<<"$(window_of morning-reconciliation)"

DATE_COMPACT="${BUSINESS_DATE//-/}"
NEXT_DATE=$(date -j -v+1d -f "%Y-%m-%d" "$BUSINESS_DATE" "+%Y-%m-%d" 2>/dev/null ||
            date -d "$BUSINESS_DATE + 1 day" "+%Y-%m-%d")

rm -rf "$WORK"
mkdir -p "$WORK/files" "$WORK/eod" "$OUT"

step "Plan"
echo "  business date        $BUSINESS_DATE (D), reconciled on $NEXT_DATE (D+1)"
echo "  population           $CUSTOMERS customers, $(( CUSTOMERS * 2 + 1 )) accounts"
echo "  dials                scale $SCALE, ${COMPRESS}x, seed $SEED"
echo "  online phase         minute 0 to $CUT_OFF, the online-cut-off instant"
echo "  overnight batch      minute $BATCH_FROM of D to $BATCH_TO of D+1"
echo "  morning recon        minute $RECON_FROM to $RECON_TO of D+1"
echo "  output               $OUT"

# -------------------------------------------------------------------------------------------------
# Phase one - the online day, against the live estate, stopping at the cut-off.
# -------------------------------------------------------------------------------------------------
step "Phase 1 of D: the online day to the cut-off"
set +e
TB_DB_PORT="$DB_PORT" \
TB_DB_CONTAINER="$DB_CONTAINER" \
TB_KEEP_DATA=1 \
TB_MANIFEST="$WORK/run-manifest.json" \
TB_SCRAPE_DIR="$WORK" \
    bash "$ROOT/workload/scripts/estate-up.sh" \
        --date "$BUSINESS_DATE" --seed "$SEED" --scale "$SCALE" --compress "$COMPRESS" \
        --from 0 --to "$CUT_OFF" \
        --customers "$CUSTOMERS" --seed-population \
        --require-postings \
    >"$WORK/online.log" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ]; then
    echo "FAIL  the online phase exited $status - see $WORK/online.log" >&2
    tail -30 "$WORK/online.log" >&2
    exit 1
fi
grep -E "^  (scheduled|opened|posted|peak)" "$WORK/online.log" || true
cp "$WORK/online.log" "$WORK/run-manifest.json" "$OUT/" 2>/dev/null || true

# -------------------------------------------------------------------------------------------------
# The same day, written as stratum-0 files. Same model, same seed, same population, same cut-off.
# -------------------------------------------------------------------------------------------------
step "The movement file, from the same stream and the same cut-off"
go -C "$ROOT/workload" run ./cmd/workload-dataset \
    --model "$MODEL" --from "$BUSINESS_DATE" --to "$BUSINESS_DATE" \
    --seed "$SEED" --scale "$SCALE" --customers "$CUSTOMERS" --to-minute "$CUT_OFF" \
    --driver-seed \
    2>"$WORK/dataset.err" \
  | python3 "$ROOT/mainframe/data/generate.py" --from-stream --out "$WORK/files" \
  | tee "$OUT/generate.txt"
cat "$WORK/dataset.err"

# -------------------------------------------------------------------------------------------------
# Phase two - the overnight cycle, in the window the day contract puts it in.
# -------------------------------------------------------------------------------------------------
step "Phase 2, minute $BATCH_FROM of D: the overnight cycle"
bash "$ROOT/mainframe/jcl/run-eod.sh" \
    --business-date "$DATE_COMPACT" \
    --master "$WORK/files/ACCTMAST.DAT" \
    --movements "$WORK/files/MOVEMENT.DAT" \
    --work "$WORK/eod" | tee "$OUT/cycle.txt"

PRODUCED="$WORK/eod/$DATE_COMPACT/ACCTNEW.DAT"
CONSUMED="$WORK/eod/$DATE_COMPACT/MOVEMENT.IN"
[ -f "$PRODUCED" ] || { echo "two-phase-day: the cycle produced no new master" >&2; exit 1; }

# -------------------------------------------------------------------------------------------------
# Phase three - the reconciliation, read-only against both sides.
# -------------------------------------------------------------------------------------------------
step "Phase 3, minute $RECON_FROM of D+1: the reconciliation"

# A role granted SELECT and nothing else, so the refusal to write comes from PostgreSQL rather than
# from the component's good intentions. batch/recon's README makes that a property of the design and
# a test asserts it; a run that reconciled as the owning role would be proving something weaker.
# -i, and it matters: without it docker exec does not attach stdin, psql reads nothing, executes
# nothing and exits 0 - so the role is silently never created and the reconciliation fails on
# authentication three steps later.
docker exec -i -e PGPASSWORD=tessera "$DB_CONTAINER" psql -U tessera -d tessera -v ON_ERROR_STOP=1 -q <<'SQL'
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'recon_reader') THEN
        EXECUTE 'DROP OWNED BY recon_reader';
        EXECUTE 'DROP ROLE recon_reader';
    END IF;
END
$$;
CREATE ROLE recon_reader LOGIN PASSWORD 'recon_reader';
GRANT CONNECT ON DATABASE tessera TO recon_reader;
GRANT USAGE ON SCHEMA public TO recon_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO recon_reader;
SQL

set +e
RECON_LEDGER_DSN="postgresql://recon_reader:recon_reader@localhost:$DB_PORT/tessera" \
    uv run --project "$ROOT/batch/recon" recon \
        --business-date "$DATE_COMPACT" \
        --master "$PRODUCED" \
        --movements "$CONSUMED" \
        --output "$WORK/recon" | tee "$OUT/recon.txt"
recon_status=${PIPESTATUS[0]}
set -e
if [ "$recon_status" -ne 0 ]; then
    echo "FAIL  the reconciliation did not run (exit $recon_status). A break exits 0; this did not." >&2
    exit 1
fi
cp "$WORK/recon/BREAKS-$DATE_COMPACT.json" "$OUT/" 2>/dev/null || true

step "Done"
echo "  the estate is still up on $DB_CONTAINER port $DB_PORT - docker rm -f $DB_CONTAINER"
echo "  captures under $OUT"
