#!/usr/bin/env bash
#
# Inject every condition in the committed catalogue, one run each, and record what it looked like.
#
# **A test fixture, not a component of the bank.** It composes the scripts either side of it rather
# than reimplementing them, exactly as baseline.sh does: estate-up.sh boots the estate and drives the
# day with the condition applied part way through, and workload-report turns the manifest and the
# scrapes into a report that judges what happened against what the scenario declared would happen.
#
#   bash workload/scripts/signatures.sh --baseline with-broker
#   bash workload/scripts/signatures.sh --baseline with-broker --only SCN-OUTBOX-STUCK
#
# It runs against the database a baseline capture left behind, so run baseline.sh first and do not
# remove tessera-dataset-db in between. A signature taken against three accounts is a signature of a
# fixture, which is the whole reason WP-22 exists.
#
# **Each run gets its own business date, and that is not cosmetic.** An idempotency key is derived
# from the business date and the event's ordinal, so a second run of the same date against the same
# ledger replays instead of posting - and the run would measure a replay path under a condition
# rather than the condition. The dates below are seven consecutive Fridays with the same 1.2 weekday
# multiplier as the baseline, none of them a payday and none in a month's last two days, so every
# run offers the same day and only the condition differs.
#
# **The captures this produces are not committed yet, and WP-24c is why.** Every one of the seven
# runs so far has had `edge/fraud-scoring` score nothing at all - tessera_fraud_scoring_seconds_count
# 0.0 - while the same estate-up.sh driven on its own consumes correctly, at a consumer group offset
# of 3 315 with zero lag. Until that is understood, two of the eleven objectives are unmeasured in
# every signature and a committed capture would be a measurement of the fixture. The script is
# committed because it works and because WP-24c needs it; its output is not.
#
# Needs: Docker, a JDK 17, Go and uv. Each run takes a couple of minutes.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASELINES="${TB_BASELINE_OUT:-$ROOT/workload/baselines}"
CATALOGUE="$ROOT/contracts/workload/tessera-scenarios-v1.json"
WORK="${TMPDIR:-/tmp}/tessera-signatures"

BASELINE=""
ONLY=""
RUN_SCALE=0.002
COMPRESS=720
WINDOW=branch-hours

# Seven Fridays, computed once and pinned here rather than derived at run time, because a capture
# whose date depends on when somebody ran it is a capture nobody can reproduce.
DATES=(2026-08-14 2026-08-07 2026-07-24 2026-07-17 2026-07-03 2026-06-26 2026-06-19)

while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) BASELINE="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --scale) RUN_SCALE="$2"; shift 2 ;;
    --compress) COMPRESS="$2"; shift 2 ;;
    --window) WINDOW="$2"; shift 2 ;;
    -h|--help) sed -n '2,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$BASELINE" ]; then
  echo "--baseline is required: it names the capture under workload/baselines/ every signature is" >&2
  echo "diffed against. A degradation described without the normal it degraded from is an anecdote." >&2
  exit 2
fi
NORMAL="$BASELINES/$BASELINE"
if [ ! -f "$NORMAL/report.txt" ]; then
  echo "no baseline at $NORMAL - run baseline.sh --out-name $BASELINE first" >&2
  exit 2
fi

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# The identifiers, read out of the catalogue rather than typed here. A count that does not match
# what the contract's own checker reports is a catalogue this script has misread, and stopping is
# better than silently running six of seven.
# read rather than mapfile: this machine's /usr/bin/env bash is 3.2, and mapfile arrived in 4.0.
SCENARIOS=()
while IFS= read -r line; do
  SCENARIOS+=("$line")
done < <(grep -o '"scenarioId": "[^"]*"' "$CATALOGUE" | cut -d'"' -f4)
if [ "${#SCENARIOS[@]}" -ne "${#DATES[@]}" ]; then
  echo "the catalogue holds ${#SCENARIOS[@]} scenarios and this script has ${#DATES[@]} dates." >&2
  echo "Add a date per new condition - two runs sharing one date replay instead of posting." >&2
  exit 2
fi

step "Plan"
echo "  baseline   $NORMAL"
echo "  scenarios  ${SCENARIOS[*]}"

rm -rf "$WORK"
mkdir -p "$WORK"

failed=0
for index in "${!SCENARIOS[@]}"; do
  id="${SCENARIOS[$index]}"
  date="${DATES[$index]}"
  if [ -n "$ONLY" ] && [ "$ONLY" != "$id" ]; then
    continue
  fi

  out="$BASELINES/signatures/$id"
  work="$WORK/$id"
  mkdir -p "$out" "$work"

  step "$id on $date"

  # TB_EXPECT_OUTBOX_BACKLOG tells estate-up.sh's drain check that a backlog is the point rather
  # than a broken fixture. Only the stuck-outbox condition leaves one, and only if it is still held
  # when the run ends - it is set for that condition alone so the check keeps working for the rest.
  expect_backlog=0
  if [ "$id" = "SCN-OUTBOX-STUCK" ]; then
    expect_backlog=1
  fi

  set +e
  TB_DB_PORT=5435 \
  TB_DB_CONTAINER=tessera-dataset-db \
  TB_KEEP_DATA=1 \
  TB_MANIFEST="$work/run-manifest.json" \
  TB_SCRAPE_DIR="$work" \
  TB_SCENARIO="$CATALOGUE" \
  TB_SCENARIO_ID="$id" \
  TB_EXPECT_OUTBOX_BACKLOG="$expect_backlog" \
    bash "$ROOT/workload/scripts/estate-up.sh" \
      --scale "$RUN_SCALE" --compress "$COMPRESS" --window "$WINDOW" --date "$date" \
    | tee "$work/run.log"
  status=${PIPESTATUS[0]}
  set -e
  if [ "$status" -ne 0 ]; then
    echo "FAIL  $id exited $status - see $work/run.log" >&2
    failed=$((failed + 1))
    continue
  fi

  go -C "$ROOT/workload" run ./cmd/workload-report \
    --manifest "$work/run-manifest.json" \
    --before "$work/before.prom" --before "$work/before-edge.prom" --before "$work/before-fraud.prom" \
    --after "$work/after.prom" --after "$work/after-edge.prom" --after "$work/after-fraud.prom" \
    --scenario "$CATALOGUE" \
    --baseline-before "$NORMAL/before.prom" \
    --baseline-before "$NORMAL/before-edge.prom" \
    --baseline-before "$NORMAL/before-fraud.prom" \
    --baseline-after "$NORMAL/after.prom" \
    --baseline-after "$NORMAL/after-edge.prom" \
    --baseline-after "$NORMAL/after-fraud.prom" \
    --catalogue "$ROOT/contracts/slo/tessera-slo-v1.json" \
    --out "$out/report.txt"

  for file in run-manifest.json before.prom after.prom \
              before-edge.prom after-edge.prom before-fraud.prom after-fraud.prom; do
    cp "$work/$file" "$out/$file"
  done
  echo "  written to $out"
done

step "Done"
if [ "$failed" -ne 0 ]; then
  echo "  $failed condition(s) did not complete" >&2
  exit 1
fi
echo "  every condition ran; the reports are under $BASELINES/signatures/"
