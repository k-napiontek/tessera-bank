#!/usr/bin/env bash
#
# Drive the same bank day over many business dates against one ledger, and record what the tables
# nothing prunes did while it ran.
#
# **A test fixture, not a component of the bank.** It composes the scripts either side of it rather
# than reimplementing them, exactly as baseline.sh and signatures.sh do: load-dataset.sh loads the
# ledger once, estate-up.sh drives each day against it, and workload-soak turns the series of daily
# scrapes into a growth report.
#
#   bash workload/scripts/soak.sh --days 12
#   bash workload/scripts/soak.sh --days 12 --customers 150000 --from 2025-09-01 --to 2026-08-21
#
# **This is what puts figures on F-28.** Nothing prunes `outbox_record` or `idempotency_record`: a
# dispatched outbox row and a completed idempotency record are kept forever, because a retention
# sweep needs a retention period and that is a regulatory question rather than an engineering one.
# The growth has been predicted since WP-09 and never measured. The retention period is still not
# decided here; only the cost of not deciding it.
#
# **Every day gets its own business date, and that is not cosmetic.** An idempotency key is derived
# from the business date and the event's ordinal, so a second run of the same date against the same
# ledger replays instead of posting - and a soak of replays grows nothing, which would read as a
# ledger whose tables are stable. `--require-postings` refuses such a run rather than reporting it.
# F-86 is what happens without that control.
#
# **One boot per date rather than one boot for twelve.** estate-up.sh boots and drives in one shot,
# and driving many dates from one boot would need a new mode in the driver. The tables live in the
# database and survive a ledger restart, so the boot cost is wall clock rather than a measurement
# error - and TB_KEEP_DATA=1 is what makes the ledger the *same* ledger across every day.
#
# Needs: Docker, a JDK 17, Go and uv. About two and a half minutes per business date, plus roughly
# eight minutes to load the ledger at the start.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASELINES="${TB_BASELINE_OUT:-$ROOT/workload/baselines}"
OUT="$BASELINES/soak"
WORK="${TMPDIR:-/tmp}/tessera-soak"

DAYS=12
CUSTOMERS=150000
LOAD_FROM=2025-09-01
LOAD_TO=2026-08-21
SEED=42
LOAD_SCALE=0.0017
RUN_SCALE=0.002
COMPRESS=720
WINDOW=branch-hours
SKIP_LOAD=0
# The first business date of the soak. Each day steps forward one weekday from here, so no date is
# ever offered twice and none of them is a weekend the model would drive at a different multiplier.
FIRST_DATE=2026-03-02

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --customers) CUSTOMERS="$2"; shift 2 ;;
    --from) LOAD_FROM="$2"; shift 2 ;;
    --to) LOAD_TO="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --load-scale) LOAD_SCALE="$2"; shift 2 ;;
    --scale) RUN_SCALE="$2"; shift 2 ;;
    --compress) COMPRESS="$2"; shift 2 ;;
    --window) WINDOW="$2"; shift 2 ;;
    --first-date) FIRST_DATE="$2"; shift 2 ;;
    --skip-load) SKIP_LOAD=1; shift ;;
    -h|--help) sed -n '2,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$DAYS" -lt 2 ]; then
  echo "--days must be at least 2: a growth rate needs two points, and one point presented as a" >&2
  echo "rate is an invention rather than a measurement." >&2
  exit 2
fi

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# The business dates, stepped forward one weekday at a time. Computed here rather than pinned,
# because the count is a flag - but the *first* date is pinned, so the same --days always produces
# the same set. date -v is BSD; this repository's other scripts already assume macOS or coreutils.
next_weekday() {
  local from="$1" candidate
  candidate=$(date -j -v+1d -f "%Y-%m-%d" "$from" "+%Y-%m-%d" 2>/dev/null ||
              date -d "$from + 1 day" "+%Y-%m-%d")
  local dow
  dow=$(date -j -f "%Y-%m-%d" "$candidate" "+%u" 2>/dev/null || date -d "$candidate" "+%u")
  while [ "$dow" -gt 5 ]; do
    candidate=$(date -j -v+1d -f "%Y-%m-%d" "$candidate" "+%Y-%m-%d" 2>/dev/null ||
                date -d "$candidate + 1 day" "+%Y-%m-%d")
    dow=$(date -j -f "%Y-%m-%d" "$candidate" "+%u" 2>/dev/null || date -d "$candidate" "+%u")
  done
  echo "$candidate"
}

DATES=("$FIRST_DATE")
current="$FIRST_DATE"
while [ "${#DATES[@]}" -lt "$DAYS" ]; do
  current=$(next_weekday "$current")
  DATES+=("$current")
done

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"

step "Plan"
echo "  days       $DAYS"
echo "  dates      ${DATES[0]} to ${DATES[${#DATES[@]}-1]}"
echo "  dials      scale $RUN_SCALE, ${COMPRESS}x, $WINDOW"
echo "  output     $OUT"

if [ "$SKIP_LOAD" -eq 0 ]; then
  step "Load the ledger once"
  TB_DATASET_MANIFEST="$OUT/dataset-manifest.json" \
    bash "$ROOT/services/ledger-loader/scripts/load-dataset.sh" \
      --customers "$CUSTOMERS" --from "$LOAD_FROM" --to "$LOAD_TO" \
      --seed "$SEED" --scale "$LOAD_SCALE"
else
  echo "  (--skip-load: running against whatever tessera-dataset-db already holds)"
fi

failed=0
for index in "${!DATES[@]}"; do
  date="${DATES[$index]}"
  day=$(printf "day-%02d" $((index + 1)))
  out="$OUT/$day"
  work="$WORK/$day"
  mkdir -p "$out" "$work"

  step "$day of $DAYS: $date"

  set +e
  TB_DB_PORT=5435 \
  TB_DB_CONTAINER=tessera-dataset-db \
  TB_KEEP_DATA=1 \
  TB_MANIFEST="$work/run-manifest.json" \
  TB_SCRAPE_DIR="$work" \
    bash "$ROOT/workload/scripts/estate-up.sh" \
      --scale "$RUN_SCALE" --compress "$COMPRESS" --window "$WINDOW" --date "$date" \
      --require-postings \
    >"$work/run.log" 2>&1
  status=$?
  set -e

  if [ "$status" -ne 0 ]; then
    echo "FAIL  $day exited $status - see $work/run.log" >&2
    tail -20 "$work/run.log" >&2
    failed=$((failed + 1))
    break
  fi

  # Only the ledger's scrapes and the manifest are kept. The soak measures what the database did and
  # nothing else, and committing twenty-four more files nothing reads would be noise in a directory
  # whose whole value is that every file in it is evidence of something.
  for file in run-manifest.json before.prom after.prom; do
    cp "$work/$file" "$out/$file"
  done

  echo "  $day captured to $out"
done

if [ "$failed" -ne 0 ]; then
  step "Stopped"
  echo "  the soak stopped after $failed failure(s); the days captured so far are under $OUT" >&2
  exit 1
fi

step "Report"
go -C "$ROOT/workload" run ./cmd/workload-soak \
  --capture "$OUT" --out "$OUT/report.txt"
cat "$OUT/report.txt"

step "Done"
echo "  the capture is under $OUT"
