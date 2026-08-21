#!/usr/bin/env bash
#
# Load a production-shaped ledger from the workload model, and check it with the ledger's own
# controls.
#
# **A test fixture, not a component of the bank.** Same category as workload/scripts/estate-up.sh and
# edge/web-banking/scripts/walkthrough.sh: something that makes a manual step short enough to be
# performed. Nothing in the estate depends on it, and no deployment uses it - packaging and
# deployment belong to the companion platform repositories (ADR 0001).
#
#   bash services/ledger-loader/scripts/load-dataset.sh
#   bash services/ledger-loader/scripts/load-dataset.sh --customers 150000 \
#        --from 2025-09-01 --to 2026-08-21 --scale 0.0017 --seed 42
#
# Needs: Docker, a JDK 17, Go 1.25.
#
# **It leaves the database running on purpose.** capture-plans.sh reads it, batch/reporting runs
# against it, and a loader that tore its own result down would make the evidence this package owes
# impossible to collect. It says how to stop it at the end.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DB_CONTAINER=tessera-dataset-db
DB_PORT=5435
DB_USER=tessera
DB_PASSWORD=tessera
DB_NAME=tessera
MANIFEST="${TB_DATASET_MANIFEST:-${TMPDIR:-/tmp}/tessera-dataset-manifest.json}"
JDBC="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME"

MODEL="$ROOT/contracts/workload/tessera-day-v1.json"
FROM=2026-06-01
TO=2026-06-30
SEED=42
SCALE=0.0017
CUSTOMERS=150000
KEEP_DATA=${TB_KEEP_DATA:-0}

while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --from) FROM="$2"; shift 2 ;;
    --to) TO="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --scale) SCALE="$2"; shift 2 ;;
    --customers) CUSTOMERS="$2"; shift 2 ;;
    --keep-data) KEEP_DATA=1; shift ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "load-dataset.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

step "PostgreSQL on $DB_PORT"
# The previous run's manifest goes before the new one is written rather than after. A run that failed
# halfway would otherwise leave a manifest describing a database that no longer exists, and the next
# reader would take it for this run's - the same trap F-73 records walkthrough.sh carrying with its
# JWT public key.
rm -f "$MANIFEST"
if [ "$KEEP_DATA" = "1" ] && docker ps -q -f "name=^${DB_CONTAINER}$" | grep -q .; then
  echo "OK    keeping the database this container already holds"
else
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$DB_CONTAINER" \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB_NAME" \
    -p "$DB_PORT":5432 postgres:16-alpine >/dev/null
  for _ in $(seq 60); do
    docker exec "$DB_CONTAINER" pg_isready -U "$DB_USER" >/dev/null 2>&1 && break
    sleep 1
  done
  echo "OK    PostgreSQL is ready on $DB_PORT"
fi

step "Build"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
  "$ROOT/gradlew" -p "$ROOT" --quiet :services:ledger-loader:installDist
LOADER="$ROOT/services/ledger-loader/build/install/ledger-loader/bin/ledger-loader"
echo "OK    $LOADER"

step "Schema"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
  "$LOADER" migrate --url "$JDBC" --user "$DB_USER" --password "$DB_PASSWORD"

step "Load"
echo "  $CUSTOMERS customers, $FROM to $TO, scale $SCALE, seed $SEED"
# The stream is piped rather than written to a file: a year of a bank's day is millions of lines and
# there is no reason for any of them to touch a disk twice. pipefail is set, so the emitter failing
# fails the run rather than feeding the loader a truncated stream.
go -C "$ROOT/workload" run ./cmd/workload-dataset \
    --model "$MODEL" --from "$FROM" --to "$TO" --seed "$SEED" \
    --scale "$SCALE" --customers "$CUSTOMERS" \
  | JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
    "$LOADER" load --url "$JDBC" --user "$DB_USER" --password "$DB_PASSWORD" --manifest "$MANIFEST"

step "The ledger's own controls"
# Not the loader's arithmetic. BalanceReconciliation sums the postings in SQL, independently of the
# Java that wrote them, and AuditChain walks the whole trail.
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
  "$LOADER" verify --url "$JDBC" --user "$DB_USER" --password "$DB_PASSWORD"

step "Loaded"
cat <<EOF
  Manifest        $MANIFEST
  DSN             postgresql://$DB_USER:$DB_PASSWORD@localhost:$DB_PORT/$DB_NAME

  Capture the query plans:
    bash services/ledger-loader/scripts/capture-plans.sh

  Run the reporting batch against it:
    cd batch/reporting && TB_REPORT_DSN=postgresql://$DB_USER:$DB_PASSWORD@localhost:$DB_PORT/$DB_NAME \\
      uv run reporting --business-date \$(echo "$TO" | tr -d -)

  Stop it:
    docker rm -f $DB_CONTAINER
EOF
