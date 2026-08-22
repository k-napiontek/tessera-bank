#!/usr/bin/env bash
#
# Capture the estate's recorded normal: a WP-21 day driven against a WP-22 ledger.
#
# **A test fixture, not a component of the bank.** It composes the two scripts either side of it
# rather than reimplementing them - services/ledger-loader/scripts/load-dataset.sh builds the
# database, workload/scripts/estate-up.sh boots the estate against it and drives the day, and
# workload-report turns the manifest and the two scrapes into the report that gets committed.
#
#   bash workload/scripts/baseline.sh --out-name spine-only
#   bash workload/scripts/baseline.sh --out-name with-broker --customers 150000 \
#     --from 2025-09-01 --to 2026-08-21 --date 2026-08-21
#
# --date is the business date the day is driven for, and pinning it is what makes a capture
# reproducible. Left to estate-up.sh it is today, and today has a weekday multiplier: the committed
# spine-only capture is a Friday at 1.2 and the same command run on a Saturday would produce a day
# at 0.45 - a third of the demand, diffed against the other and read as a regression.
#
# --out-name is required and names a directory under workload/baselines/. One directory per capture,
# because a second baseline written over the first is not a second baseline: two measurements that
# do not state their conditions cannot be compared, and the conditions are exactly what differs
# between two captures worth keeping.
#
# Why it composes rather than copies: three scripts in this repository already boot a database and
# a JVM, and F-61 records what the fourth copy of that scaffolding costs when one of them is fixed.
#
# Needs: Docker, a JDK 17, Go and uv. Takes several minutes - most of it is the load.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASELINES="${TB_BASELINE_OUT:-$ROOT/workload/baselines}"
WORK="${TMPDIR:-/tmp}/tessera-baseline"
OUT_NAME=""

CUSTOMERS=20000
FROM=2026-06-01
TO=2026-06-30
SEED=42
DATE=""
LOAD_SCALE=0.0017
RUN_SCALE=0.002
COMPRESS=720
WINDOW=branch-hours

while [ $# -gt 0 ]; do
  case "$1" in
    --customers) CUSTOMERS="$2"; shift 2 ;;
    --from) FROM="$2"; shift 2 ;;
    --to) TO="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --load-scale) LOAD_SCALE="$2"; shift 2 ;;
    --scale) RUN_SCALE="$2"; shift 2 ;;
    --compress) COMPRESS="$2"; shift 2 ;;
    --window) WINDOW="$2"; shift 2 ;;
    --out-name) OUT_NAME="$2"; shift 2 ;;
    --date) DATE="$2"; shift 2 ;;
    -h|--help) sed -n '2,28p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$OUT_NAME" ]; then
  echo "--out-name is required: it names the directory under workload/baselines/ this capture" >&2
  echo "goes in, and it is what stops a second baseline being written over the first." >&2
  exit 2
fi
case "$OUT_NAME" in
  */*|.|..) echo "--out-name is a single directory name, not a path: $OUT_NAME" >&2; exit 2 ;;
esac
OUT="$BASELINES/$OUT_NAME"

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"
echo "Capturing into $OUT"

step "Load a production-shaped ledger (WP-22)"
TB_DATASET_MANIFEST="$WORK/dataset-manifest.json" \
  bash "$ROOT/services/ledger-loader/scripts/load-dataset.sh" \
    --customers "$CUSTOMERS" --from "$FROM" --to "$TO" \
    --seed "$SEED" --scale "$LOAD_SCALE"

step "Drive a compressed day against it (WP-21)"
# TB_KEEP_DATA=1 is what stops estate-up.sh emptying the ledger it was pointed at. Without it the
# baseline would be captured against three accounts, which is the state WP-22 exists to end.
TB_DB_PORT=5435 \
TB_DB_CONTAINER=tessera-dataset-db \
TB_KEEP_DATA=1 \
TB_MANIFEST="$WORK/run-manifest.json" \
TB_SCRAPE_DIR="$WORK" \
  bash "$ROOT/workload/scripts/estate-up.sh" \
    --scale "$RUN_SCALE" --compress "$COMPRESS" --window "$WINDOW" \
    ${DATE:+--date "$DATE"} \
  | tee "$WORK/run.log"

step "Generate the report"
# Three pairs since WP-24a. The estate exposes its metrics in three places and a report built from
# one of them says "nothing happened" about the other two, which reads as an estate that did nothing
# rather than as a report that looked in one place.
go -C "$ROOT/workload" run ./cmd/workload-report \
  --manifest "$WORK/run-manifest.json" \
  --before "$WORK/before.prom" --before "$WORK/before-edge.prom" --before "$WORK/before-fraud.prom" \
  --after "$WORK/after.prom" --after "$WORK/after-edge.prom" --after "$WORK/after-fraud.prom" \
  --catalogue "$ROOT/contracts/slo/tessera-slo-v1.json" \
  --out "$OUT/report.txt"

for file in run-manifest.json dataset-manifest.json \
            before.prom after.prom before-edge.prom after-edge.prom \
            before-fraud.prom after-fraud.prom; do
  cp "$WORK/$file" "$OUT/$file"
done

step "Captured"
echo "  $OUT/report.txt"
echo "  $OUT/run-manifest.json, dataset-manifest.json"
echo "  $OUT/{before,after}{,-edge,-fraud}.prom"
echo
echo "  Stop the database:  docker rm -f tessera-dataset-db"
