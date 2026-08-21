#!/usr/bin/env bash
#
# Boot the modern spine and drive a compressed bank day at it.
#
# **A test fixture, not a component of the bank.** It is the same kind of artefact as
# edge/web-banking/scripts/walkthrough.sh and dev-token.mjs: something that makes a manual step
# short enough to actually be performed. Nothing in the estate depends on it, and no deployment
# uses it - packaging and deployment belong to the companion platform repositories (ADR 0001).
#
#   bash workload/scripts/estate-up.sh
#   bash workload/scripts/estate-up.sh --scale 0.0005 --compress 360 --window branch-hours
#
# Every argument is passed straight through to workload-run, so its --help is this script's help
# for everything except the four variables below.
#
# Needs: Docker, a JDK 17, Go 1.25. Ctrl-C stops everything it started.
#
# The ordering matters and is the one thing this script exists to get right. The gateway verifies
# tokens against a public key file that has to exist before it starts, and the driver is what mints
# them - so the driver goes first, writes the public half, and waits for the gateway to answer. The
# private half never leaves the driver's process and is never written to disk.

set -euo pipefail

# Job control, so that every background job below is its own process group. `go run` and `gradlew`
# both exec a child - the compiled binary and a JVM - and killing the parent alone leaves that child
# holding the port. The next run then talks to the previous run's gateway, which still holds the
# previous run's public key, and every request in it is a 401 that reads like a broken driver. Found
# by running this script twice.
set -m

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KEYS="${TMPDIR:-/tmp}/tessera-workload-keys.pem"
RUN_LOG="${TMPDIR:-/tmp}/tessera-workload-run.log"
LEDGER_PORT=8080
GATEWAY_PORT=8081
GATEWAY_ADMIN_PORT=9091
METRICS_PORT=9100
# Overridable so that a run can be pointed at a database somebody else loaded - which is what
# WP-23's baseline does with the WP-22 dataset. Left alone, this boots one of its own.
DB_PORT=${TB_DB_PORT:-5434}
DB_CONTAINER=${TB_DB_CONTAINER:-tessera-workload-db}
PIDS=()

# shellcheck disable=SC2329  # reached through the trap below, never called directly
cleanup() {
  echo
  echo "== stopping =="
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] || continue
    # The group first, then the process itself in case it was never given one.
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  done
  if [ "${TB_KEEP_DATA:-0}" != "1" ]; then
    docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  fi
  # Wait for the ports rather than sleeping on a guess. A JVM takes a moment to let go of 8080, and
  # the next run of this script otherwise fails four minutes later with "port already in use", in a
  # log nobody has opened yet.
  for port in "$LEDGER_PORT" "$GATEWAY_PORT" "$METRICS_PORT"; do
    for _ in $(seq 20); do
      lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 || break
      sleep 1
    done
  done
}
trap cleanup EXIT INT TERM

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

wait_for() {
  local what="$1" url="$2" attempts=${3:-60}
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

wait_for_file() {
  local what="$1" path="$2" attempts=${3:-60}
  for _ in $(seq "$attempts"); do
    [ -s "$path" ] && { echo "OK    $what"; return 0; }
    sleep 1
  done
  echo "FAIL  $what: $path never appeared" >&2
  return 1
}

# A port still held by a previous run is the failure this script is most likely to hit, and the way
# it presents - a Spring Boot stack trace in a log file, four minutes in - is the least useful one.
for port in "$LEDGER_PORT" "$GATEWAY_PORT" "$METRICS_PORT"; do
  for attempt in $(seq 30); do
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 || break
    if [ "$attempt" = "30" ]; then
      echo "FAIL  port $port is still in use; stop whatever is holding it and run this again" >&2
      exit 1
    fi
    sleep 1
  done
done

step "PostgreSQL"
# TB_KEEP_DATA=1 keeps the ledger a previous run left behind, idempotency records included - which
# is what makes a second run of the same seed and date replay rather than post. Without it the
# container is recreated, because a "keep the data" flag that skipped a TRUNCATE while the volume
# went with the container would keep nothing.
if [ "${TB_KEEP_DATA:-0}" = "1" ] && docker ps -q -f "name=^${DB_CONTAINER}$" | grep -q .; then
  echo "OK    keeping the PostgreSQL this estate already has on $DB_PORT"
else
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$DB_CONTAINER" \
    -e POSTGRES_PASSWORD=tessera -e POSTGRES_USER=tessera -e POSTGRES_DB=tessera \
    -p "$DB_PORT":5432 postgres:16-alpine >/dev/null
  for _ in $(seq 60); do
    docker exec "$DB_CONTAINER" pg_isready -U tessera >/dev/null 2>&1 && break
    sleep 1
  done
  echo "OK    PostgreSQL is ready on $DB_PORT"
fi

step "Ledger"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
LEDGER_DB_URL="jdbc:postgresql://localhost:$DB_PORT/tessera" \
LEDGER_DB_USER=tessera \
LEDGER_DB_PASSWORD=tessera \
  "$ROOT/gradlew" -p "$ROOT" :services:ledger-api:bootRun >"${TMPDIR:-/tmp}/tessera-workload-ledger.log" 2>&1 &
PIDS+=("$!")
wait_for "the ledger" "http://localhost:$LEDGER_PORT/actuator/health/readiness" 240

# A run's idempotency keys are derived from the business date and the event's ordinal, which is what
# makes it reproducible - and means a second run of the same date against the same ledger replays
# rather than posts. Starting from an empty ledger keeps a baseline a baseline; TB_KEEP_DATA=1 keeps
# what is there and turns the replay column into the demonstration instead.
if [ "${TB_KEEP_DATA:-0}" != "1" ]; then
  step "Empty ledger"
  docker exec -e PGPASSWORD=tessera "$DB_CONTAINER" \
    psql -U tessera -d tessera -c \
    "TRUNCATE audit_record, outbox_record, idempotency_record, posting, journal_entry, hold, balance, account CASCADE" \
    >/dev/null 2>&1 || true
  echo "OK    the ledger holds nothing; this run starts from zero"
fi

step "Driver"
echo "  it writes the public key, then waits for the gateway - its output follows below"
# The key from a previous run is removed first. Without this, wait_for_file sees the old file
# immediately, the gateway starts holding the public half of a key pair that no longer exists, and
# every request in the run is a 401 that reads like a broken driver. Found by running this script
# twice.
rm -f "$KEYS"
go -C "$ROOT/workload" run ./cmd/workload-run \
  --model "$ROOT/contracts/workload/tessera-day-v1.json" \
  --date "$(date +%Y-%m-%d)" \
  --gateway "http://localhost:$GATEWAY_PORT" \
  --ledger-metrics "http://localhost:$LEDGER_PORT/actuator/prometheus" \
  --edge-metrics "http://localhost:$GATEWAY_ADMIN_PORT/metrics" \
  --keys "$KEYS" \
  --metrics ":$METRICS_PORT" \
  --manifest "${TB_MANIFEST:-${TMPDIR:-/tmp}/tessera-workload-manifest.json}" \
  ${TB_SCRAPE_DIR:+--scrapes "$TB_SCRAPE_DIR"} \
  "$@" >"$RUN_LOG" 2>&1 &
DRIVER=$!
PIDS+=("$DRIVER")
wait_for_file "the driver wrote its public key" "$KEYS" 120

step "Gateway"
TB_GATEWAY_LEDGER_URL="http://localhost:$LEDGER_PORT/v1" \
TB_GATEWAY_JWT_ISSUER="https://issuer.tesserabank.example" \
TB_GATEWAY_JWT_AUDIENCE="tessera-bank-ledger" \
TB_GATEWAY_JWT_KEYS="$KEYS" \
TB_GATEWAY_LISTEN=":$GATEWAY_PORT" \
TB_GATEWAY_ADMIN_LISTEN=":$GATEWAY_ADMIN_PORT" \
TB_GATEWAY_RATE_PER_SECOND="${TB_GATEWAY_RATE_PER_SECOND:-20}" \
TB_GATEWAY_RATE_BURST="${TB_GATEWAY_RATE_BURST:-40}" \
  go -C "$ROOT/edge/api-gateway" run ./cmd/gateway >"${TMPDIR:-/tmp}/tessera-workload-gateway.log" 2>&1 &
PIDS+=("$!")
wait_for "the gateway" "http://localhost:$GATEWAY_ADMIN_PORT/readyz" 60

step "Run"
echo "  seeding and then executing; this takes as long as the compressed day does"
echo "  metrics: http://localhost:$METRICS_PORT/metrics   and the gateway's own on :$GATEWAY_ADMIN_PORT"
set +e
wait "$DRIVER"
STATUS=$?
set -e

echo
cat "$RUN_LOG"
echo
echo "  ledger log   ${TMPDIR:-/tmp}/tessera-workload-ledger.log"
echo "  gateway log  ${TMPDIR:-/tmp}/tessera-workload-gateway.log"
echo "  manifest     ${TB_MANIFEST:-${TMPDIR:-/tmp}/tessera-workload-manifest.json}"

exit "$STATUS"
