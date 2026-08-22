#!/usr/bin/env bash
#
# Apply a schema migration to a live ledger while a compressed bank day is being driven at it, and
# record the lock it took and what the customer experienced while it held it.
#
# **A test fixture, not a component of the bank.** It composes the scripts either side of it rather
# than reimplementing them, exactly as baseline.sh and signatures.sh do: estate-up.sh boots the
# estate and drives the day, workload-migration migrates part way through it, and workload-report
# turns the manifest and the scrapes into the run's own report.
#
#   bash workload/scripts/migration.sh --baseline with-broker
#   bash workload/scripts/migration.sh --baseline with-broker --variant blocking
#
# It runs against the database a baseline capture left behind, so run baseline.sh first and do not
# remove tessera-dataset-db in between. A migration timed against three accounts is a migration
# against a fixture: the whole question is what CREATE INDEX costs over millions of rows.
#
# **Each variant gets its own business date.** An idempotency key is derived from the business date
# and the event's ordinal, so two runs sharing a date replay instead of posting - and the run would
# then measure a migration against a replay path rather than against a bank. Same rule, same reason
# as signatures.sh's seven pinned Fridays.
#
# **The migration is applied mid-run and the day carries on around it.** A migration applied between
# two runs measures a maintenance window, which is the thing this exercise exists not to be. The
# moment is taken from the driver printing "== Run ==" rather than from a sleep, because Gradle and
# Kafka boot times are not a constant and a capture whose moment depends on how busy the laptop was
# is a capture nobody can reproduce.
#
# Needs: Docker, a JDK 17, Go and uv. Each variant takes a couple of minutes.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASELINES="${TB_BASELINE_OUT:-$ROOT/workload/baselines}"
WORK="${TMPDIR:-/tmp}/tessera-migration"
RUN_LOG="${TMPDIR:-/tmp}/tessera-workload-run.log"
# The manifest load-dataset.sh wrote for the ledger this runs against. Copied into each capture so
# that it names the database it migrated - a lock duration over six million rows and one over three
# accounts are not the same measurement, and only this file says which was taken.
DATASET_MANIFEST="${TB_DATASET_MANIFEST:-${TMPDIR:-/tmp}/tessera-dataset-manifest.json}"

BASELINE=""
VARIANT="both"
RUN_SCALE=0.002
COMPRESS=720
WINDOW=branch-hours
# How far into the compressed day the migration is applied. A branch-hours day at 720x is about 45
# seconds of wall clock, so fifteen seconds is a third of the way in - past seeding, with the arrival
# process at full rate, and with as much of the day as possible still to come.
#
# **Deliberately early, because a CREATE INDEX over millions of rows may well outlast the day.** If
# it does, the run's settle and drain windows are still ahead of it and the estate is still up, so
# the closing scrapes are taken against a live gateway either way. The customer-side figures are
# ratios of counter deltas, so an idle tail neither helps nor hurts them - what would ruin the
# capture is the estate being torn down while the migration is still running, and this is the margin
# against that.
AFTER=15s

# One pinned date per variant, both Fridays with the same 1.2 weekday multiplier the baseline used,
# neither a payday and neither in a month's last two days. Pinned rather than derived at run time,
# because a capture whose date depends on when somebody ran it is a capture nobody can reproduce.
DATE_BLOCKING=2026-06-12
DATE_CONCURRENT=2026-06-05

while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) BASELINE="$2"; shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --scale) RUN_SCALE="$2"; shift 2 ;;
    --compress) COMPRESS="$2"; shift 2 ;;
    --window) WINDOW="$2"; shift 2 ;;
    --after) AFTER="$2"; shift 2 ;;
    -h|--help) sed -n '2,29p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$BASELINE" ]; then
  echo "--baseline is required: it names the capture under workload/baselines/ the run is read" >&2
  echo "against. A latency described without the normal it departed from is an anecdote." >&2
  exit 2
fi
NORMAL="$BASELINES/$BASELINE"
if [ ! -f "$NORMAL/report.txt" ]; then
  echo "no baseline at $NORMAL - run baseline.sh --out-name $BASELINE first" >&2
  exit 2
fi

case "$VARIANT" in
  blocking|concurrent|both) ;;
  *) echo "--variant is blocking, concurrent or both; got $VARIANT" >&2; exit 2 ;;
esac

VARIANTS=()
case "$VARIANT" in
  both) VARIANTS=(blocking concurrent) ;;
  *) VARIANTS=("$VARIANT") ;;
esac

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

rm -rf "$WORK"
mkdir -p "$WORK"

step "Plan"
echo "  baseline   $NORMAL"
echo "  variants   ${VARIANTS[*]}"
echo "  applied    $AFTER into a ${COMPRESS}x $WINDOW day"

failed=0
for variant in "${VARIANTS[@]}"; do
  if [ "$variant" = "blocking" ]; then
    date="$DATE_BLOCKING"
  else
    date="$DATE_CONCURRENT"
  fi

  out="$BASELINES/migration/$variant"
  work="$WORK/$variant"
  mkdir -p "$out" "$work"

  step "$variant on $date"

  # The exercise is started first and waits for the day, rather than the other way round: the driver
  # is what says when the day begins, and nothing else in this script knows.
  rm -f "$RUN_LOG"
  go -C "$ROOT/workload" run ./cmd/workload-migration \
    --variant "$variant" \
    --migrations "$ROOT/workload/migrations/$variant" \
    --db-container tessera-dataset-db \
    --run-log "$RUN_LOG" \
    --after "$AFTER" \
    --edge-metrics http://localhost:9091/metrics \
    --ledger-metrics http://localhost:8080/actuator/prometheus \
    --catalogue "$ROOT/contracts/slo/tessera-slo-v1.json" \
    --out "$work" \
    >"$work/migration.log" 2>&1 &
  EXERCISE=$!

  set +e
  TB_DB_PORT=5435 \
  TB_DB_CONTAINER=tessera-dataset-db \
  TB_KEEP_DATA=1 \
  TB_MANIFEST="$work/run-manifest.json" \
  TB_SCRAPE_DIR="$work" \
    bash "$ROOT/workload/scripts/estate-up.sh" \
      --scale "$RUN_SCALE" --compress "$COMPRESS" --window "$WINDOW" --date "$date" \
      --require-postings \
    | tee "$work/run.log"
  status=${PIPESTATUS[0]}

  # The exercise outlives the run only if something went wrong; either way its status is what says
  # whether a migration was actually applied.
  wait "$EXERCISE"
  exercise_status=$?
  set -e

  cat "$work/migration.log"

  if [ "$status" -ne 0 ]; then
    echo "FAIL  the $variant run exited $status - see $work/run.log" >&2
    failed=$((failed + 1))
    continue
  fi
  if [ "$exercise_status" -ne 0 ]; then
    echo "FAIL  the $variant migration exited $exercise_status - see $work/migration.log" >&2
    failed=$((failed + 1))
    continue
  fi

  go -C "$ROOT/workload" run ./cmd/workload-report \
    --manifest "$work/run-manifest.json" \
    --before "$work/before.prom" --before "$work/before-edge.prom" --before "$work/before-fraud.prom" \
    --after "$work/after.prom" --after "$work/after-edge.prom" --after "$work/after-fraud.prom" \
    --catalogue "$ROOT/contracts/slo/tessera-slo-v1.json" \
    --out "$out/report.txt"

  for file in run-manifest.json migration.json locks.txt \
              before.prom after.prom before-edge.prom after-edge.prom \
              before-fraud.prom after-fraud.prom \
              before-edge-migration.prom after-edge-migration.prom \
              before-ledger-migration.prom after-ledger-migration.prom; do
    cp "$work/$file" "$out/$file"
  done
  if [ -f "$DATASET_MANIFEST" ]; then
    cp "$DATASET_MANIFEST" "$out/dataset-manifest.json"
  else
    echo "  NOTE  no dataset manifest at $DATASET_MANIFEST, so this capture does not name the" >&2
    echo "        ledger it ran against. Set TB_DATASET_MANIFEST when loading." >&2
  fi
  echo "  written to $out"
done

step "Done"
if [ "$failed" -ne 0 ]; then
  echo "  $failed variant(s) did not complete" >&2
  exit 1
fi
echo "  the captures are under $BASELINES/migration/"
