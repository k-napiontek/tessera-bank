#!/usr/bin/env bash
#
# Capture EXPLAIN (ANALYZE, BUFFERS) for the queries this estate reads a ledger with, against the
# database load-dataset.sh left behind.
#
# **A test fixture, not a component of the bank**, like the script beside it.
#
#   bash services/ledger-loader/scripts/load-dataset.sh
#   bash services/ledger-loader/scripts/capture-plans.sh
#
# The account it captures against is the busiest one the load produced, read out of the load
# manifest. It is not chosen here and it is not planted: an account planted to be deep would be a
# plan of the fixture this package exists to replace, and the manifest names the depth so a reader
# can see what "deep" meant on the day.
#
# Needs: Docker, python3 (to read one field out of the manifest).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DB_CONTAINER=tessera-dataset-db
DB_USER=tessera
DB_NAME=tessera
MANIFEST="${TB_DATASET_MANIFEST:-${TMPDIR:-/tmp}/tessera-dataset-manifest.json}"
OUT="${1:-${TMPDIR:-/tmp}/tessera-query-plans.txt}"

if [ ! -s "$MANIFEST" ]; then
  echo "capture-plans.sh: no manifest at $MANIFEST - run load-dataset.sh first" >&2
  exit 1
fi
if ! docker ps -q -f "name=^${DB_CONTAINER}$" | grep -q .; then
  echo "capture-plans.sh: $DB_CONTAINER is not running - run load-dataset.sh first" >&2
  exit 1
fi

read -r ACCOUNT FROM TO BUSINESS_DATE POSTINGS <<EOF
$(python3 - "$MANIFEST" <<'PY'
import json
import sys

manifest = json.load(open(sys.argv[1]))
# The last business date is what batch/reporting would be run for, and what the two reporting
# queries below are bounded by.
print(manifest["busiestAccountRef"], manifest["from"], manifest["to"], manifest["to"],
      manifest["busiestAccountPostings"])
PY
)
EOF

echo "Capturing against $ACCOUNT, which holds $POSTINGS postings, over $FROM to $TO"

# Truncated before it is written rather than after. A capture that failed halfway would otherwise
# leave the previous run's plans under this run's name, which is the trap F-73 records.
rm -f "$OUT"

docker exec -i -e PGOPTIONS="--client-min-messages=warning" "$DB_CONTAINER" \
  psql -U "$DB_USER" -d "$DB_NAME" --quiet --no-psqlrc \
    -v account="$ACCOUNT" -v from="$FROM" -v to="$TO" -v business_date="$BUSINESS_DATE" \
  < "$ROOT/services/ledger-loader/scripts/plans.sql" | tee "$OUT"

echo
echo "Written to $OUT"
echo "Record it, with the row counts it was measured at, in docs/architecture/query-plans-at-volume.md"
