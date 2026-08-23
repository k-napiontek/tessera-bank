#!/usr/bin/env bash
#
# WP-18a's incident exercise: break the estate on purpose, in a way it can genuinely break, and see
# whether the documented process finds it.
#
# **A test fixture, not a component of the bank.** It composes what already exists - legacy-up.sh,
# estate-up.sh, adapter-up.sh, run-eod.sh and batch/recon - and changes nothing in any of them.
#
#   bash workload/scripts/incident-exercise.sh --keep      # break it, and leave it broken
#   bash workload/scripts/incident-exercise.sh --recover   # reverse the fault and re-run the control
#   bash workload/scripts/incident-exercise.sh --customers 8000 --date 2026-03-02
#
# # The fault
#
# **F-106, which is a defect this estate already has rather than one manufactured for the exercise.**
# `legacy/customer-master` declares CHECK (last_movement_date IS NULL OR last_movement_date >=
# opened_date). One account's opened_date is moved forward by a day, *after* seeding and at the
# moment the business day begins - the shape of an operator data-fix applied to the wrong row. Every
# subsequent movement touching that account is refused ORA-02290; the refusal arrives at the adapter
# as a **generic** SOAP fault rather than the WSDL's declared ServiceFault, so it is classified
# transient, never acknowledged, and retried for ever at Spring Kafka's default zero backoff.
#
# The partition blocks behind it by design, because ordering is what that buys. **Nothing fails.** No
# error rate moves, nothing is dead-lettered, the ledger goes on posting normally and the customer
# sees nothing. Transfers simply stop reaching the mainframe.
#
# # Why it runs two business dates
#
# batch/recon counts a posting towards what the master ought to hold when its reference is in the
# movement file **or** its value date is earlier than the business date - ledger.py:146, which is
# ADR 0015 in SQL. A transfer this fault blocks is in neither set on the day it happens: not in the
# file, because the fault is what stopped it getting there, and not earlier-dated, because it is
# today's.
#
# **So the reconciliation passes on day D.** The bank is short and the control that exists to say so
# is, correctly by its own rules, silent. On D+1 those postings are dated earlier than the business
# date, enter the expected set, and the master still does not hold them - so the break surfaces a
# full cycle late as VALUE_DRIFT. Day D's clean report is evidence, not a failed attempt.
#
# # The envelope
#
# What was planted - the account, the transfer that trips it, the original date - is written to
# ENVELOPE.json, and **nothing else in the capture is derived from it**. The exercise is only worth
# anything if the break is found from the reconciliation report and the back office instead.
#
# # Why it holds the estate up
#
# **--keep, and the response is the reason.** WP-18a's task 4 says the incident is worked *as
# documented*: detected from the reconciliation report and legacy/backoffice, triaged, contained,
# resolved, and the recovery verified by re-running the control that found it. None of that can be
# done against an estate that was torn down the moment the report was written, so a held run leaves
# Oracle, Tomcat, Kafka, PostgreSQL and the adapter exactly where the break left them.
#
# The operator screen is deployed for the same reason. **A responder who reads BREAKS-CCYYMMDD.json
# is not using this bank's detection path**; an operator reads /backoffice/breaks, and what that
# screen does and does not show is itself part of what the exercise measures.
#
# # The reversal
#
# **--recover is a separate invocation against the held estate**, run after the response, and it is
# the Constraint's "the fault must be reversible and its removal verified". It restores the
# opened_date, drives one more business date, and shows that the account the fault refused is posting
# to the mainframe again - a row read back says what was written, and only a movement that crosses
# says the estate accepts one.
#
# **It does not assert zero drift, and the reason is the finding.** The transfers the fault refused
# were not held: the adapter exhausted a FixedBackOff(0, 9), discarded them and committed the offset,
# so there is no backlog to drain and nothing to replay. The fault reverses; **its cost does not**.
# The reconciliation is therefore read against day D's own floor - what this estate looks like with
# no fault in it, which is not zero either, because F-104 and F-107 put a population of accounts in
# VALUE_DRIFT every morning for reasons that predate this exercise.
#
# Needs: Docker, a JDK 8, a JDK 17, Go, uv and GnuCOBOL.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${TB_INCIDENT_OUT:-$ROOT/workload/baselines/incident}"
WORK="${TMPDIR:-/tmp}/tessera-incident"

MODEL="$ROOT/contracts/workload/tessera-day-v1.json"
BUSINESS_DATE=2026-03-02
CUSTOMERS=8000
SCALE=0.002
COMPRESS=720
WINDOW=branch-hours
SEED=42
# How far into the driven window the fault should first be tripped, as a fraction. Early enough that
# most of the day is behind it, late enough that the day is genuinely under way when it happens.
TRIP_AT=0.10
KEEP=no
RECOVER=no
OPERATOR=operator

DB_CONTAINER=tessera-incident-db
DB_PORT=5438
KAFKA_CONTAINER=tessera-incident-kafka
KAFKA_PORT=9096
KAFKA_INTERNAL=localhost:9094
TOMCAT_PORT=18080
ORACLE_CONTAINER="${TB_ORACLE_CONTAINER:-tessera-legacy-oracle}"

usage() { sed -n '3,73p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --customers) CUSTOMERS="$2"; shift 2 ;;
        --scale)     SCALE="$2"; shift 2 ;;
        --compress)  COMPRESS="$2"; shift 2 ;;
        --date)      BUSINESS_DATE="$2"; shift 2 ;;
        --seed)      SEED="$2"; shift 2 ;;
        --trip-at)   TRIP_AT="$2"; shift 2 ;;
        --out)       OUT="$2"; shift 2 ;;
        --keep)      KEEP=yes; shift ;;
        --recover)   RECOVER=yes; shift ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "incident-exercise: unknown argument $1" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

command -v cobc >/dev/null || { echo "incident-exercise: GnuCOBOL is not installed" >&2; exit 1; }
command -v uv   >/dev/null || { echo "incident-exercise: uv is not installed" >&2; exit 1; }

JAVA8="${JAVA8_HOME:-$(/usr/libexec/java_home -v 1.8 2>/dev/null)}"
[ -n "$JAVA8" ] || { echo "incident-exercise: no JDK 8 - see make jdk8" >&2; exit 1; }

COMPETING="$(docker ps --format '{{.Names}}' 2>/dev/null \
  | grep -v -E "^(${DB_CONTAINER}|${KAFKA_CONTAINER}|${ORACLE_CONTAINER})$" || true)"
if [ -n "$COMPETING" ]; then
    echo "incident-exercise: these containers are running and this fixture needs the memory:" >&2
    echo "$COMPETING" | sed 's/^/    /' >&2
    echo "  Stop them and run again. This script will not remove a container it did not start." >&2
    exit 1
fi

NEXT_DATE=$(date -j -v+1d -f "%Y-%m-%d" "$BUSINESS_DATE" "+%Y-%m-%d" 2>/dev/null ||
            date -d "$BUSINESS_DATE + 1 day" "+%Y-%m-%d")
RECOVERY_DATE=$(date -j -v+2d -f "%Y-%m-%d" "$BUSINESS_DATE" "+%Y-%m-%d" 2>/dev/null ||
                date -d "$BUSINESS_DATE + 2 days" "+%Y-%m-%d")
D_COMPACT="${BUSINESS_DATE//-/}"
D1_COMPACT="${NEXT_DATE//-/}"
D2_COMPACT="${RECOVERY_DATE//-/}"

# --recover runs against the estate a held run left standing, so it keeps everything: the master the
# cycle has to carry forward, the movement file the drain will fill, and the adapter's own pid.
[ "$RECOVER" = yes ] || rm -rf "$WORK"
mkdir -p "$WORK/files" "$WORK/eod" "$OUT"
MOVEMENT_FILE="$WORK/MOVEMENT.DAT"
ADAPTER_LOG="$WORK/adapter.log"
ENDPOINT="http://localhost:$TOMCAT_PORT/customer-master/services/CustomerMasterService"
# One flat directory for both dates, because that is what the operator screen reads: BusinessDates
# scans it for BREAKS-CCYYMMDD.json and offers what it finds. A directory per date would show the
# responder one morning at a time, which is not how a morning works.
RECON_DIR="$WORK/recon"
BREAKS_URL="http://localhost:$TOMCAT_PORT/backoffice/breaks"
mkdir -p "$RECON_DIR"

HELD=no
stop() {
    if [ "$HELD" = yes ]; then
        echo; echo "== left running =="
        echo "  back office   $BREAKS_URL as $OPERATOR"
        echo "  endpoint      $ENDPOINT"
        echo "  ledger        localhost:$DB_PORT, broker localhost:$KAFKA_PORT"
        echo "  adapter log   $ADAPTER_LOG"
        echo "  breaks        $RECON_DIR"
        echo "  rejects       $WORK/eod/<CCYYMMDD>/REJECTS.DAT"
        echo
        echo "  reverse it with: bash workload/scripts/incident-exercise.sh --recover"
        return
    fi
    echo; echo "== stopping =="
    [ -f "$WORK/adapter.pid" ] && kill "$(cat "$WORK/adapter.pid")" 2>/dev/null || true
    LEGACY_WORK="${TMPDIR:-/tmp}/tessera-legacy"
    CATALINA="$(find "$LEGACY_WORK" -maxdepth 2 -type f -path '*/bin/catalina.sh' 2>/dev/null | head -1)"
    [ -n "$CATALINA" ] && JAVA_HOME="$JAVA8" CATALINA_PID="$LEGACY_WORK/tomcat.pid" \
        "$CATALINA" stop 20 -force >/dev/null 2>&1 || true
    docker rm -f "$ORACLE_CONTAINER" "$KAFKA_CONTAINER" "$DB_CONTAINER" >/dev/null 2>&1 || true
    echo "-- stopped"
}
trap stop EXIT INT TERM

records() { if [ -f "$1" ]; then echo $(( $(wc -c <"$1") / 120 )); else echo 0; fi; }

# -------------------------------------------------------------------------------------------------
# **The cut-off is only a cut-off once the hop has stopped writing.** estate-up.sh returns when the
# ledger's outbox is drained, which means every posting is on the topic - it says nothing about the
# adapter, which is a whole era behind and writes the movement file at about 170 records a second.
# Rotating the instant the driver stops therefore cuts the day in the middle of the hop.
#
# The first run of this exercise did exactly that and gave the cycle **330 of the day's 8 706
# transfers**. Everything still classified correctly - the 9 580 accounts it left behind came back as
# TIMING, which is what TIMING means - but the report was 9 580 lines of noise around the six lines
# that mattered, and the next morning read as 8 748 accounts of VALUE_DRIFT at twice the day's value.
#
# **It waits for the file to go quiet rather than for the lag to reach zero**, because a partition
# this fault has blocked never reaches zero and a fixture that waited for it would hang until its
# timeout and then cut off anyway, having learnt nothing.
# -------------------------------------------------------------------------------------------------
# -------------------------------------------------------------------------------------------------
# Seeding's movements out, the day's in - by MOV-VALUE-DATE, which is the only thing that actually
# distinguishes them. Funding is dated the day before the run (`Header.openingDate`, F-103's rule);
# every record the cycle should see is dated D or later.
#
# **The first version of this rotated the whole file when the day began, and it silently ate 449 of
# the day's own transfers.** The adapter runs about a minute behind the ledger, so at the instant the
# day starts the file still holds seeding records the adapter has only just written - and the day's
# first records land in it before anything notices. Moving the file takes both. The exercise it
# produced reported 451 transfers that never reached the mainframe when the injected fault had cost
# **two**, and the responder spent the middle of the incident proving that 449 of them were the
# harness rather than the bank.
#
# Filtering by value date has no race in it at all: a record is seeding's or it is the day's, and
# when it was written makes no difference to which.
# -------------------------------------------------------------------------------------------------
take_the_days_records() {
    local source="$1" kept="$2" from="$3"
    python3 - "$source" "$kept" "$WORK/MOVEMENT-seeding.DAT" "$from" <<'SPLIT'
import sys
source, kept, seeding, from_date = sys.argv[1:]
data = open(source, "rb").read()
mine, theirs = bytearray(), bytearray()
for i in range(0, len(data), 120):
    record = data[i:i + 120]
    (mine if record[50:58].decode() >= from_date else theirs).extend(record)
open(kept, "wb").write(mine)
open(seeding, "ab").write(theirs)
print(f"  {len(mine)//120} of the day's records to the cycle, "
      f"{len(theirs)//120} of seeding's held back")
SPLIT
}

wait_for_the_hop() {
    local quiet=0 last=-1 now
    for _ in $(seq 150); do
        now=$(records "$MOVEMENT_FILE")
        if [ "$now" -eq "$last" ]; then quiet=$(( quiet + 1 )); else quiet=0; fi
        [ "$quiet" -ge 5 ] && break
        last="$now"
        sleep 2
    done
    echo "  the hop went quiet at $(records "$MOVEMENT_FILE") records"
}

run_cycle_and_reconcile() {
    local date_compact="$1" master="$2" label="$3"

    wait_for_the_hop

    # The movement file is rotated at the cut-off, which is what makes it the cut-off: the cycle
    # consumes what the ESB wrote up to this instant and the adapter starts a fresh one. ADR 0015.
    local movements="$WORK/MOVEMENT-$date_compact.DAT" cut="$WORK/CUT-$date_compact.DAT"
    if [ -f "$MOVEMENT_FILE" ]; then mv "$MOVEMENT_FILE" "$cut"; else : >"$cut"; fi
    take_the_days_records "$cut" "$movements" "$D_COMPACT"

    bash "$ROOT/mainframe/jcl/run-eod.sh" --business-date "$date_compact" \
        --master "$master" --movements "$movements" --work "$WORK/eod" \
        >"$OUT/cycle-$date_compact.txt" 2>&1
    grep -E "RC=|MOVE-APPLIED|MOVE-REJECTED" "$OUT/cycle-$date_compact.txt" | tail -4 || true

    set +e
    RECON_LEDGER_DSN="postgresql://recon_reader:recon_reader@localhost:$DB_PORT/tessera" \
        uv run --project "$ROOT/batch/recon" recon \
            --business-date "$date_compact" \
            --master "$WORK/eod/$date_compact/ACCTNEW.DAT" \
            --movements "$WORK/eod/$date_compact/MOVEMENT.IN" \
            --output "$RECON_DIR" >"$OUT/recon-$date_compact.txt" 2>&1
    local status=$?
    set -e
    [ "$status" -eq 0 ] || { echo "FAIL  the $label reconciliation did not run" >&2; cat "$OUT/recon-$date_compact.txt" >&2; exit 1; }
    cp "$RECON_DIR/BREAKS-$date_compact.json" "$OUT/" 2>/dev/null || true
    grep -E "accounts|breaks|drift|matched" "$OUT/recon-$date_compact.txt" | head -8 || true
}

# -------------------------------------------------------------------------------------------------
# The reversal, run against the estate a held run left standing. The Constraint says the fault must
# be reversible and its removal verified, and `incident-management.md` says a recovery is verified by
# re-running the control that found the problem rather than by the symptom going away.
#
# **Nothing is deleted and no offset moves.** The account's opened_date goes back to what it was, the
# refusal stops happening, and the retry that was blocking the partition becomes the retry that
# drains it - which is the whole reason this fault was chosen over one that needs queue surgery.
# -------------------------------------------------------------------------------------------------
# GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG ... - the topic is $2 and the lag is $6.
#
# **It prints -1 rather than 0 when it could not read the group at all.** An awk that summed nothing
# and printed zero would tell the drain loop below that a broker it cannot reach has caught up, which
# is the same failure adapter-up.sh's readiness probe carries a paragraph about.
lag() {
    docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
        --bootstrap-server "$KAFKA_INTERNAL" --describe --group esb-adapter 2>/dev/null \
      | awk '$2 ~ /^tessera/ && $6 ~ /^[0-9]+$/ { total += $6; seen = 1 }
             END { if (seen) print total; else print -1 }'
}

recover() {
    [ -f "$OUT/ENVELOPE.json" ] || { echo "incident-exercise: no envelope under $OUT - there is nothing to reverse" >&2; exit 1; }
    [ -f "$WORK/eod/$D1_COMPACT/ACCTNEW.DAT" ] || { echo "incident-exercise: no day D+1 master under $WORK - run the exercise with --keep first" >&2; exit 1; }

    local account original
    account=$(python3 -c "import json;print(json.load(open('$OUT/ENVELOPE.json'))['accountRef'])")
    original=$(python3 -c "import json;print(json.load(open('$OUT/ENVELOPE.json'))['originalOpenedDate'])")

    step "The reversal"
    echo "  account        $account"
    echo "  opened_date    back to $original"
    echo "  behind the consumer, before: $(lag) messages"

    docker exec -i "$ORACLE_CONTAINER" sqlplus -S "tessera/tessera@//localhost:1521/FREEPDB1" \
        >"$WORK/reverse.log" 2>&1 <<SQL
WHENEVER SQLERROR EXIT FAILURE
UPDATE account SET opened_date = DATE '$original' WHERE account_ref = '$account';
COMMIT;
EXIT
SQL
    echo "  reversed at    $(date -u +%H:%M:%S)Z"

    # -----------------------------------------------------------------------------------------
    # **The removal is proved by driving another day, not by reading the row back.** The row says
    # what was written; only a movement that crosses says the estate accepts one again - and
    # `incident-management.md` is explicit that a recovery stated without re-running the control is
    # a recovery nobody measured.
    #
    # It is deliberately not a drain. The first run of this reversal waited for a backlog that was
    # not there: the refused messages had already exhausted their backoff and been discarded, so the
    # consumer sat at zero lag with the money gone. What there is to verify is that the *next*
    # transfer succeeds, and that is what this drives.
    # -----------------------------------------------------------------------------------------
    step "Day D+2: the same estate, with the fault out of it"
    set +e
    TB_DB_PORT="$DB_PORT" TB_DB_CONTAINER="$DB_CONTAINER" \
    TB_KAFKA_PORT="$KAFKA_PORT" TB_KAFKA_CONTAINER="$KAFKA_CONTAINER" \
    TB_KEEP_DATA=1 TB_KEEP_BROKER=1 TB_MANIFEST="$WORK/manifest-d2.json" TB_SCRAPE_DIR="$WORK/d2" \
        bash "$ROOT/workload/scripts/estate-up.sh" \
            --date "$RECOVERY_DATE" --seed "$SEED" --scale "$SCALE" --compress "$COMPRESS" \
            --window "$WINDOW" --customers "$CUSTOMERS" --require-postings --skip-seeding \
        >"$WORK/day-d2.log" 2>&1
    local day_d2=$?
    set -e
    [ "$day_d2" -eq 0 ] || { echo "FAIL  day D+2 exited $day_d2 - see $WORK/day-d2.log" >&2; tail -20 "$WORK/day-d2.log" >&2; exit 1; }
    grep -E "^  (posted|scheduled)" "$WORK/day-d2.log" || true
    wait_for_the_hop

    local crossed
    crossed=$(grep -ac "$account" "$MOVEMENT_FILE" 2>/dev/null || true)
    echo "  movement records for $account since the reversal: ${crossed:-0}"
    [ "${crossed:-0}" -gt 0 ] || {
        echo "FAIL  the account still cannot move money - the reversal did not take" >&2; exit 1; }
    echo "OK    the account the fault refused is posting to the mainframe again"

    step "Day D+2: the cycle, and the reconciliation that found the break"
    run_cycle_and_reconcile "$D2_COMPACT" "$WORK/eod/$D1_COMPACT/ACCTNEW.DAT" "recovery"

    # -----------------------------------------------------------------------------------------
    # **Against the floor, not against zero.** Day D's own report is what this estate looks like
    # with no fault in it, and it is not clean: F-104 dates a hold capture and a reversal by the
    # machine's clock while F-107 puts them in the movement file anyway, so a population of accounts
    # is in VALUE_DRIFT on every reconciliation for reasons that predate this exercise. A recovery
    # that had to reach zero would be measuring those two findings rather than this one.
    # -----------------------------------------------------------------------------------------
    step "Recovered?"
    python3 - "$RECON_DIR/BREAKS-$D_COMPACT.json" "$RECON_DIR/BREAKS-$D1_COMPACT.json" \
             "$RECON_DIR/BREAKS-$D2_COMPACT.json" <<'DRIFT'
import json, sys

def drift(path):
    return {b["accountRef"] for b in json.load(open(path))["breaks"]
            if b["classification"] == "VALUE_DRIFT"}

floor, broken, now = (drift(p) for p in sys.argv[1:4])
print(f"  the floor, day D            {len(floor)} accounts in drift with no fault in the estate")
print(f"  the break, day D+1          {len(broken)}")
print(f"  after the reversal, day D+2 {len(now)}")
print(f"  cleared by the reversal     {len(broken - now)}")
print(f"  still in drift and not the floor {len(now - floor)}")
if now - floor:
    print("\n  Those did not clear and cannot: their postings were discarded by the adapter after")
    print("  the backoff ran out, the offsets were committed, and nothing in this estate replays a")
    print("  message the consumer has already acknowledged. The fault is reversible. Its cost is not.")
DRIFT
}

if [ "$RECOVER" = yes ]; then
    recover
    exit 0
fi

step "Plan"
echo "  business dates   $BUSINESS_DATE (D) and $NEXT_DATE (D+1)"
echo "  population       $CUSTOMERS customers, $(( CUSTOMERS * 2 + 1 )) accounts"
echo "  dials            scale $SCALE, ${COMPRESS}x, $WINDOW, seed $SEED"
echo "  the fault        one account's opened_date moved forward, at ${TRIP_AT} into the window"
echo "  output           $OUT"
[ "$KEEP" = yes ] && echo "  afterwards       held up, for the response to be worked against" || true

# -------------------------------------------------------------------------------------------------
# Stratum 1, and the day's own schedule - which is where the victim comes from. Choosing the account
# out of the schedule rather than at random is what makes the exercise repeatable: the same seed
# trips the same transfer at the same point of the same day.
# -------------------------------------------------------------------------------------------------
step "Stratum 1: Oracle and Tomcat 8.5"
bash "$ROOT/workload/scripts/legacy-up.sh" --keep \
    --customers "$CUSTOMERS" --date "$BUSINESS_DATE" --seed "$SEED" \
    --backoffice --breaks-dir "$RECON_DIR" --rejects-dir "$WORK/eod" \
    >"$WORK/legacy-up.log" 2>&1
curl -sf -o /dev/null "$ENDPOINT?wsdl" \
    || { echo "incident-exercise: stratum 1 did not come up - see $WORK/legacy-up.log" >&2; exit 1; }
echo "OK    the WSDL answers on $TOMCAT_PORT"
curl -sf -o /dev/null -u "$OPERATOR:$OPERATOR" "$BREAKS_URL" \
    || { echo "incident-exercise: the back office did not deploy - see $WORK/legacy-up.log" >&2; exit 1; }
echo "OK    the back office answers on $BREAKS_URL"

step "Choosing what to break"
go -C "$ROOT/workload" run ./cmd/workload-dataset \
    --model "$MODEL" --from "$BUSINESS_DATE" --to "$BUSINESS_DATE" \
    --seed "$SEED" --scale "$SCALE" --customers "$CUSTOMERS" --driver-seed \
    2>/dev/null >"$WORK/day.ndjson"

python3 - "$WORK/day.ndjson" "$MODEL" "$WINDOW" "$TRIP_AT" >"$WORK/victim.json" <<'PY'
import json, sys

stream, model_path, window_id, trip_at = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
model = json.load(open(model_path))
window = next(w for w in model["calendar"]["windows"] if w["id"] == window_id)
lo, hi = window["startMinute"] * 60000, window["endMinute"] * 60000

transfers = []
for line in open(stream):
    action = json.loads(line)
    if action.get("kind") == "action" and action.get("operation") == "createTransfer":
        if lo <= action["atMillis"] <= hi:
            transfers.append(action)

if not transfers:
    sys.exit("incident-exercise: the window carries no transfer to trip the fault with")

# The victim has to be an account whose **first** touch in the window is the transfer we name, or
# the partition would block earlier than the envelope claims and the exercise would be describing a
# different day than the one it ran. Either leg refuses: pkg_posting.apply_transfer stamps
# last_movement_date on both.
seen = set()
chosen = None
target = int(len(transfers) * trip_at)
for position, action in enumerate(transfers):
    first_touch = action["accountRef"] not in seen and action["counterpartyRef"] not in seen
    if position >= target and first_touch:
        chosen = action
        break
    seen.add(action["accountRef"])
    seen.add(action["counterpartyRef"])

if chosen is None:
    sys.exit("incident-exercise: no transfer past the trip point opens an account not already touched")
json.dump({
    "accountRef": chosen["accountRef"],
    "transferRef": chosen["transferRef"],
    "atMillis": chosen["atMillis"],
    "positionInWindow": transfers.index(chosen) + 1,
    "transfersInWindow": len(transfers),
}, sys.stdout, indent=2)
PY

VICTIM=$(python3 -c "import json;print(json.load(open('$WORK/victim.json'))['accountRef'])")
# **Neither reference is printed, and that is the exercise's only control.** The first run of this
# script echoed the account and the transfer it had chosen, which put both on the responder's screen
# a quarter of an hour before the reconciliation they were supposed to be found from. A sealed
# envelope beside a console that reads it out is not sealed.
echo "  the day carries $(python3 -c "import json;print(json.load(open('$WORK/victim.json'))['transfersInWindow'])") transfers in $WINDOW"
echo "  one of them is chosen; which one is in the envelope and nowhere else"

ORIGINAL_OPENED=$(docker exec -i "$ORACLE_CONTAINER" sqlplus -S "tessera/tessera@//localhost:1521/FREEPDB1" <<SQL 2>/dev/null | tr -d ' \n'
SET HEADING OFF PAGESIZE 0 FEEDBACK OFF
SELECT TO_CHAR(opened_date,'YYYY-MM-DD') FROM account WHERE account_ref = '$VICTIM';
EXIT
SQL
)

# The envelope. Sealed, and nothing downstream reads it - the whole exercise is whether the break is
# found without it.
python3 - "$WORK/victim.json" "$ORIGINAL_OPENED" "$NEXT_DATE" "$BUSINESS_DATE" "$SEED" >"$OUT/ENVELOPE.json" <<'PY'
import json, sys
victim = json.load(open(sys.argv[1]))
victim["originalOpenedDate"] = sys.argv[2]
victim["injectedOpenedDate"] = sys.argv[3]
# The conditions, so the capture describes the run that produced it rather than the script's
# defaults - which is the rule every other directory under baselines/ already follows.
victim["businessDate"] = sys.argv[4]
victim["seed"] = int(sys.argv[5])
victim["mechanism"] = (
    "account_movement_ck refuses a movement dated before opened_date; the refusal reaches the "
    "adapter as a generic SOAP fault, is classified transient, and blocks the partition for ever")
victim["dataFix"] = (
    "opened_date moved forward one day and last_movement_date cleared in the same UPDATE - the "
    "watermark has to go or the same constraint refuses the correction itself")
victim["reversal"] = "restore originalOpenedDate; the next redelivery then succeeds and the partition drains"
json.dump(victim, sys.stdout, indent=2)
PY
echo "  sealed to $OUT/ENVELOPE.json"

# -------------------------------------------------------------------------------------------------
# The injector, armed to fire when the day starts rather than before it. Injecting earlier would
# refuse the account's own funding, which is dated D-1, and block the partition during seeding - so
# the day would never get under way and the exercise would measure an empty file.
# -------------------------------------------------------------------------------------------------
inject_when_the_day_starts() {
    # **The marker is the driver's, not the script's.** estate-up.sh prints its own bold "== Run =="
    # step *before* launching the driver, and the driver prints "== Run ==" again after seeding has
    # finished. Waiting on the first would inject during seeding - which refuses the victim account's
    # own funding, dated D-1, and blocks the partition before the day is under way. This line is
    # printed by the driver at the instant the measured day begins and by nothing else.
    for _ in $(seq 900); do
        if grep -q "of business time at" "$WORK/day-d.log" 2>/dev/null; then break; fi
        sleep 1
    done
    sleep 1
    # Seeding's own movements have to be kept out of the cycle - the master already opens each
    # account at its funded balance, so giving the cycle the funding too applies it twice. They are
    # separated **at the cut-off and by value date**, not here and not by moving the file: see
    # take_the_days_records below for what moving it cost.

    # **last_movement_date is cleared in the same statement, and that is not tidying up.**
    # ACCOUNT_MOVEMENT_CK is the constraint this whole fault turns on, and it guards the row as
    # hard as it guards the posting: seeding has already stamped the account's funding date on it,
    # so moving opened_date forward on its own is refused ORA-02290 - **the data fix is stopped by
    # the same rule the transfers will be**. Clearing the watermark in the same UPDATE satisfies the
    # constraint's own NULL branch and lets the row through, which is exactly how a real operator
    # gets a bad correction past a check they were not thinking about.
    #
    # The first run of this exercise did not clear it, sent the UPDATE into >/dev/null, and sqlplus
    # exits 0 on a SQL error unless it is told otherwise - so the fixture announced an injection
    # that Oracle had refused and the run measured an estate with no fault in it. Everything
    # downstream looked entirely plausible. Hence WHENEVER SQLERROR EXIT FAILURE and the read-back.
    docker exec -i "$ORACLE_CONTAINER" sqlplus -S "tessera/tessera@//localhost:1521/FREEPDB1" \
        >"$WORK/inject.log" 2>&1 <<SQL
WHENEVER SQLERROR EXIT FAILURE
UPDATE account SET opened_date = DATE '$NEXT_DATE', last_movement_date = NULL
 WHERE account_ref = '$VICTIM';
COMMIT;
EXIT
SQL
    local planted
    planted=$(docker exec -i "$ORACLE_CONTAINER" sqlplus -S "tessera/tessera@//localhost:1521/FREEPDB1" 2>/dev/null <<SQL | tr -d ' \n'
SET HEADING OFF PAGESIZE 0 FEEDBACK OFF
SELECT TO_CHAR(opened_date,'YYYY-MM-DD') FROM account WHERE account_ref = '$VICTIM';
EXIT
SQL
)
    if [ "$planted" != "$NEXT_DATE" ]; then
        echo "the fault did not take - opened_date is $planted, not $NEXT_DATE" >"$WORK/injected.txt"
        return 1
    fi
    echo "injected at $(date -u +%H:%M:%S)Z, confirmed by reading the row back" >"$WORK/injected.txt"
}

step "Stratum 2, the injector, and day D"
bash "$ROOT/workload/scripts/adapter-up.sh" \
    --broker-container "$KAFKA_CONTAINER" --bootstrap "$KAFKA_INTERNAL" \
    --kafka-host "localhost:$KAFKA_PORT" \
    --endpoint "$ENDPOINT" --movement-file "$MOVEMENT_FILE" \
    --log "$ADAPTER_LOG" --pid-file "$WORK/adapter.pid" >"$WORK/adapter-up.log" 2>&1 &
ADAPTER_UP=$!

inject_when_the_day_starts &
INJECTOR=$!

set +e
TB_DB_PORT="$DB_PORT" TB_DB_CONTAINER="$DB_CONTAINER" \
TB_KAFKA_PORT="$KAFKA_PORT" TB_KAFKA_CONTAINER="$KAFKA_CONTAINER" \
TB_KEEP_DATA=1 TB_KEEP_BROKER=1 TB_MANIFEST="$WORK/manifest-d.json" TB_SCRAPE_DIR="$WORK" \
    bash "$ROOT/workload/scripts/estate-up.sh" \
        --date "$BUSINESS_DATE" --seed "$SEED" --scale "$SCALE" --compress "$COMPRESS" \
        --window "$WINDOW" --customers "$CUSTOMERS" --seed-population --require-postings \
    >"$WORK/day-d.log" 2>&1
day_d=$?
set -e
wait "$ADAPTER_UP" || { echo "incident-exercise: stratum 2 never came up" >&2; cat "$WORK/adapter-up.log" >&2; exit 1; }
wait "$INJECTOR" || { echo "FAIL  the injector did not plant the fault - see $WORK/inject.log" >&2; cat "$WORK/injected.txt" >&2; exit 1; }
cat "$WORK/adapter-up.log"
[ "$day_d" -eq 0 ] || { echo "FAIL  day D exited $day_d - see $WORK/day-d.log" >&2; tail -20 "$WORK/day-d.log" >&2; exit 1; }
grep -E "^  (opened|posted|scheduled)" "$WORK/day-d.log" || true
echo "  $(cat "$WORK/injected.txt" 2>/dev/null || echo 'the injector did not fire')"

# The role batch/recon reads as, granted SELECT and nothing else, so the refusal to write comes from
# PostgreSQL rather than from the component's good intentions.
docker exec -i -e PGPASSWORD=tessera "$DB_CONTAINER" psql -U tessera -d tessera -v ON_ERROR_STOP=1 -q <<'SQL'
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'recon_reader') THEN
        EXECUTE 'DROP OWNED BY recon_reader'; EXECUTE 'DROP ROLE recon_reader';
    END IF;
END
$$;
CREATE ROLE recon_reader LOGIN PASSWORD 'recon_reader';
GRANT CONNECT ON DATABASE tessera TO recon_reader;
GRANT USAGE ON SCHEMA public TO recon_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO recon_reader;
SQL

# The account master the cycle starts from. Its balances come from the stream's own header, which is
# the figure the driver funded with - F-98. Only ACCTMAST is taken from here: the movement file is
# the adapter's, which is the whole point of the exercise.
step "The master for day D"
go -C "$ROOT/workload" run ./cmd/workload-dataset \
    --model "$MODEL" --from "$BUSINESS_DATE" --to "$BUSINESS_DATE" \
    --seed "$SEED" --scale "$SCALE" --customers "$CUSTOMERS" --driver-seed 2>/dev/null \
  | python3 "$ROOT/mainframe/data/generate.py" --from-stream --out "$WORK/files" >"$WORK/generate.txt"
tail -3 "$WORK/generate.txt"


step "Day D: the overnight cycle and the morning reconciliation"
run_cycle_and_reconcile "$D_COMPACT" "$WORK/files/ACCTMAST.DAT" "day D"

# --skip-seeding, because the accounts are already open and funded from day D. Without it the driver
# tries to open them again under an idempotency key derived from the new date, every open conflicts
# with the account that is already there, and the run dies with "14226 of 14227 accounts could not be
# prepared" - which is what the first run of this exercise did.
step "Day D+1: the same estate, one day later"
set +e
TB_DB_PORT="$DB_PORT" TB_DB_CONTAINER="$DB_CONTAINER" \
TB_KAFKA_PORT="$KAFKA_PORT" TB_KAFKA_CONTAINER="$KAFKA_CONTAINER" \
TB_KEEP_DATA=1 TB_KEEP_BROKER=1 TB_MANIFEST="$WORK/manifest-d1.json" TB_SCRAPE_DIR="$WORK/d1" \
    bash "$ROOT/workload/scripts/estate-up.sh" \
        --date "$NEXT_DATE" --seed "$SEED" --scale "$SCALE" --compress "$COMPRESS" \
        --window "$WINDOW" --customers "$CUSTOMERS" --require-postings --skip-seeding \
    >"$WORK/day-d1.log" 2>&1
day_d1=$?
set -e
[ "$day_d1" -eq 0 ] || { echo "FAIL  day D+1 exited $day_d1 - see $WORK/day-d1.log" >&2; tail -20 "$WORK/day-d1.log" >&2; exit 1; }
grep -E "^  (posted|scheduled)" "$WORK/day-d1.log" || true

run_cycle_and_reconcile "$D1_COMPACT" "$WORK/eod/$D_COMPACT/ACCTNEW.DAT" "day D+1"

step "The estate as the responder finds it"
echo "  adapter: crossed $(grep -c 'crossed to stratum 0' "$ADAPTER_LOG" 2>/dev/null || echo 0), retried $(grep -c 'will be retried' "$ADAPTER_LOG" 2>/dev/null || echo 0), dead-lettered $(grep -c 'dead-lettering' "$ADAPTER_LOG" 2>/dev/null || echo 0)"
cp "$ADAPTER_LOG" "$WORK/adapter-full.log" 2>/dev/null || true
grep -E "transfer TB|WARN|ERROR" "$ADAPTER_LOG" 2>/dev/null | head -40 >"$OUT/adapter-head.txt" || true
cp "$WORK/day-d.log" "$OUT/day-d.log" 2>/dev/null || true
cp "$WORK/day-d1.log" "$OUT/day-d1.log" 2>/dev/null || true

step "Done"
echo "  captures under $OUT"
echo "  the adapter's full log, not committed, is $ADAPTER_LOG"

# Only here, and only on the success path. A run that failed on the way to the break has nothing
# worth holding open, and leaving four containers behind after an error is how the next run finds
# the machine already full.
HELD="$KEEP"
