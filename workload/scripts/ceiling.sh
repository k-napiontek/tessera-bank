#!/usr/bin/env bash
#
# Measure where the ledger stops going faster, with one instance and then with two.
#
# **A test fixture, not a component of the bank**, like estate-up.sh beside it. It boots PostgreSQL
# and one or two ledger instances against it, walks workload-ceiling up a concurrency ladder, and
# writes the measurement out as JSON.
#
#   bash workload/scripts/ceiling.sh
#   bash workload/scripts/ceiling.sh --levels 1,2,4,8,16,32 --duration 15s
#
# This exists for F-27, open since WP-09: the audit chain takes pg_advisory_xact_lock and holds it
# to commit, so money-moving transactions cannot interleave. ADR 0005 states that ceiling rather
# than discovering it, and the follow-up asks for "a measured number, not a hunch".
#
# There is no gateway here on purpose. The question is about a lock on the write path, and an edge
# in front of it would add a rate limiter that caps the run long before the lock does.
#
# Needs: Docker, a JDK 17, Go. Ctrl-C stops everything it started.

set -euo pipefail
set -m

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${TB_CEILING_OUT:-${TMPDIR:-/tmp}}"
DB_CONTAINER=tessera-ceiling-db
DB_PORT=5436
LEDGER_ONE=8090
LEDGER_TWO=8091
PIDS=()

# shellcheck disable=SC2329  # reached through the trap below
cleanup() {
  echo
  echo "== stopping =="
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] || continue
    # The group first: gradlew execs a JVM, and killing the parent alone leaves the child on the
    # port. The next run then measures the previous run's ledger. estate-up.sh found this the hard
    # way and F-73 records walkthrough.sh still carrying it.
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  done
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  for port in "$LEDGER_ONE" "$LEDGER_TWO"; do
    for _ in $(seq 30); do
      lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 || break
      sleep 1
    done
  done
}
trap cleanup EXIT INT TERM

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

wait_for() {
  local what="$1" url="$2" attempts=${3:-240}
  for _ in $(seq "$attempts"); do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then
      echo "OK    $what is up"
      return 0
    fi
    sleep 1
  done
  echo "FAIL  $what did not come up: $url" >&2
  return 1
}

boot_ledger() {
  local port="$1" log="$2"
  JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
  SERVER_PORT="$port" \
  LEDGER_DB_URL="jdbc:postgresql://localhost:$DB_PORT/tessera" \
  LEDGER_DB_USER=tessera \
  LEDGER_DB_PASSWORD=tessera \
    "$ROOT/gradlew" -p "$ROOT" :services:ledger-api:bootRun \
      --args="--server.port=$port --tessera.outbox.relay-enabled=false" \
      >"$log" 2>&1 &
  PIDS+=("$!")
  wait_for "the ledger on $port" "http://localhost:$port/actuator/health/readiness"
}

HARDWARE="$(uname -s) $(uname -m), $(sysctl -n hw.ncpu 2>/dev/null || nproc) cores, $(go version | awk '{print $3}')"
GIT_SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

step "PostgreSQL"
docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$DB_CONTAINER" \
  -e POSTGRES_PASSWORD=tessera -e POSTGRES_USER=tessera -e POSTGRES_DB=tessera \
  -p "$DB_PORT":5432 postgres:16-alpine >/dev/null
for _ in $(seq 60); do
  docker exec "$DB_CONTAINER" pg_isready -U tessera >/dev/null 2>&1 && break
  sleep 1
done
echo "OK    PostgreSQL is ready on $DB_PORT"

step "One ledger instance"
boot_ledger "$LEDGER_ONE" "${TMPDIR:-/tmp}/tessera-ceiling-one.log"

go -C "$ROOT/workload" run ./cmd/workload-ceiling \
  --ledger "http://localhost:$LEDGER_ONE" \
  --prefix TB91 \
  --hardware "$HARDWARE" --git-sha "$GIT_SHA" \
  --out "$OUT_DIR/ceiling-one-instance.json" \
  "$@"

step "A second instance on the same database"
# The point of the second instance. The advisory lock is taken in the database, not in the JVM, so
# two ledgers queue on the same lock - and a ceiling that does not move when a second writer is
# added is the answer F-27 is asking for rather than a disappointment.
boot_ledger "$LEDGER_TWO" "${TMPDIR:-/tmp}/tessera-ceiling-two.log"

go -C "$ROOT/workload" run ./cmd/workload-ceiling \
  --ledger "http://localhost:$LEDGER_ONE" \
  --ledger "http://localhost:$LEDGER_TWO" \
  --prefix TB92 \
  --hardware "$HARDWARE" --git-sha "$GIT_SHA" \
  --out "$OUT_DIR/ceiling-two-instances.json" \
  "$@"

step "Written"
echo "  $OUT_DIR/ceiling-one-instance.json"
echo "  $OUT_DIR/ceiling-two-instances.json"
