#!/usr/bin/env bash
#
# Boot the modern spine, the broker behind it and the scorer behind that, and drive a compressed
# bank day at the lot.
#
# **A test fixture, not a component of the bank.** It is the same kind of artefact as
# edge/web-banking/scripts/walkthrough.sh and dev-token.mjs: something that makes a manual step
# short enough to actually be performed. Nothing in the estate depends on it, and no deployment
# uses it - packaging and deployment belong to the companion platform repositories (ADR 0001).
#
#   bash workload/scripts/estate-up.sh
#   bash workload/scripts/estate-up.sh --scale 0.0005 --compress 360 --window branch-hours
#
# To degrade it while the day is running, name a condition from the committed catalogue:
#
#   TB_SCENARIO=contracts/workload/tessera-scenarios-v1.json \
#   TB_SCENARIO_ID=SCN-OUTBOX-STUCK \
#     bash workload/scripts/estate-up.sh --scale 0.002 --compress 720 --window branch-hours
#
# Every argument is passed straight through to workload-run, so its --help is this script's help
# for everything except the four variables below.
#
# Needs: Docker, a JDK 17, Go 1.25 and uv. Ctrl-C stops everything it started.
#
# Kafka and edge/fraud-scoring were added by WP-24a, which closes the broker half of F-77. Without
# them the estate exercised two of the five components in the SLO catalogue, and three of WP-24's
# seven injected conditions had no observable at all: consumer lag has no consumer, and a stuck
# outbox row was this fixture's permanent state rather than an injected condition. Neither component
# changed to make that work - both already read their configuration from the environment.
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
# The controllable hop the driver hosts, and what the gateway is pointed at instead of the ledger.
# In path for every run including the baseline, so that a signature and the normal it is diffed
# against differ by the condition and by nothing else.
LEDGER_PROXY_PORT=${TB_LEDGER_PROXY_PORT:-8079}
GATEWAY_PORT=8081
GATEWAY_ADMIN_PORT=9091
METRICS_PORT=9100
# The scorer's own default is 9100, which is the driver's. Two processes cannot both have it, and
# the collision presents as a scorer that will not start in a log nobody has opened yet.
FRAUD_METRICS_PORT=${TB_FRAUD_METRICS_PORT:-9102}
# Overridable so that a run can be pointed at a database somebody else loaded - which is what
# WP-23's baseline does with the WP-22 dataset. Left alone, this boots one of its own.
DB_PORT=${TB_DB_PORT:-5434}
DB_CONTAINER=${TB_DB_CONTAINER:-tessera-workload-db}
# Overridable for the same reason the database is: a second estate has to be able to run beside the
# first without either of them noticing.
KAFKA_PORT=${TB_KAFKA_PORT:-9092}
KAFKA_CONTAINER=${TB_KAFKA_CONTAINER:-tessera-workload-kafka}
# The scorer is behind a relay and a broker, so the closing scrape has to be taken after both have
# had somewhere to deliver. Stated rather than guessed, and passed through to the driver.
SETTLE=${TB_SETTLE:-15s}
# One <role>.pgid per process this script starts, so that an injected condition can suspend the
# ledger or the scorer. The **group** rather than the process: `go run`, `gradlew` and `uv run` each
# exec a child, and signalling the parent alone stops a wrapper and leaves the thing that matters
# running. F-73 records the same trap costing a whole run when it was teardown rather than injection.
RUN_DIR=${TB_RUN_DIR:-${TMPDIR:-/tmp}/tessera-workload-run}
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
  # The broker goes whatever TB_KEEP_DATA says: it holds no state anybody wants to keep, and a
  # paused container left behind by an interrupted injection is the next run's mystery.
  docker rm -f "$KAFKA_CONTAINER" >/dev/null 2>&1 || true
  if [ "${TB_KEEP_DATA:-0}" != "1" ]; then
    docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  fi
  # Wait for the ports rather than sleeping on a guess. A JVM takes a moment to let go of 8080, and
  # the next run of this script otherwise fails four minutes later with "port already in use", in a
  # log nobody has opened yet.
  for port in "$LEDGER_PORT" "$LEDGER_PROXY_PORT" "$GATEWAY_PORT" "$METRICS_PORT" "$FRAUD_METRICS_PORT"; do
    for _ in $(seq 20); do
      lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 || break
      sleep 1
    done
  done
}
trap cleanup EXIT INT TERM

rm -rf "$RUN_DIR"
mkdir -p "$RUN_DIR"

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

# One gauge out of the ledger's own exposition. A metric the scrape does not carry reads as "?"
# rather than as 0, because a missing reading and a reading of zero are different answers and only
# one of them means the relay drained.
ledger_gauge() {
  # A Micrometer series carries its labels in the first field - `ledger_outbox_pending{application=
  # "ledger-api",} 0.0` - so matching the bare name finds nothing and the check reports "?" for a
  # relay that was working perfectly. Found by running this.
  curl -fsS "http://localhost:$LEDGER_PORT/actuator/prometheus" 2>/dev/null \
    | awk -v name="$1" '$1 == name || index($1, name "{") == 1 { print $2; found = 1 }
                        END { if (!found) print "?" }' \
    | head -1
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
for port in "$LEDGER_PORT" "$LEDGER_PROXY_PORT" "$GATEWAY_PORT" "$METRICS_PORT" "$FRAUD_METRICS_PORT"; do
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

step "Kafka"
# CLUSTER_ID is a base64-encoded UUID because that is what kafka-storage format requires; a readable
# name there fails the format step and the container exits before anything says why.
# confluentinc/cp-kafka:7.6.1 is the image four Testcontainers suites in this repository already
# pull, so a fixture that booted a different one would be exercising a broker nothing else here has
# ever run against. Single-node KRaft: no ZooKeeper, and two listeners rather than one - clients on
# the host reach PLAINTEXT through the published port, and the broker reaches itself on INTERNAL,
# which is what stops a non-default TB_KAFKA_PORT breaking the traffic it sends to itself.
#
# Every in-container address is localhost rather than the container's name. A container on the
# default bridge network cannot resolve its own name - there is no DNS there, only on a user-defined
# network - and the symptom is a broker that starts, logs UnknownHostException in a loop and never
# answers a metadata request. Found by running this.
docker rm -f "$KAFKA_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$KAFKA_CONTAINER" \
  -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS="1@localhost:9093" \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,INTERNAL://0.0.0.0:9094 \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://localhost:$KAFKA_PORT,INTERNAL://localhost:9094" \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
  -p "$KAFKA_PORT":9092 confluentinc/cp-kafka:7.6.1 >/dev/null
# Waited on rather than slept on, and waited on with a client call rather than with a log grep: a
# broker that has printed "started" is not necessarily one that will answer a metadata request.
kafka_up=0
for _ in $(seq 90); do
  if docker exec "$KAFKA_CONTAINER" \
      kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1; then
    kafka_up=1
    break
  fi
  sleep 1
done
if [ "$kafka_up" != "1" ]; then
  echo "FAIL  the broker never answered a metadata request on $KAFKA_PORT" >&2
  echo "      docker logs $KAFKA_CONTAINER" >&2
  exit 1
fi
echo "OK    Kafka is ready on $KAFKA_PORT"

step "Ledger"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
LEDGER_DB_URL="jdbc:postgresql://localhost:$DB_PORT/tessera" \
LEDGER_DB_USER=tessera \
LEDGER_DB_PASSWORD=tessera \
LEDGER_KAFKA_BOOTSTRAP="localhost:$KAFKA_PORT" \
  "$ROOT/gradlew" -p "$ROOT" :services:ledger-api:bootRun >"${TMPDIR:-/tmp}/tessera-workload-ledger.log" 2>&1 &
PIDS+=("$!")
echo "$!" >"$RUN_DIR/ledger.pgid"
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

step "Fraud scoring"
# uv run syncs and then execs the console script. The scorer needs exactly one variable it has no
# default for, and it refuses to boot on a setting it cannot parse rather than falling back - which
# is why nothing here has to check that it read what it was given.
TB_FRAUD_BROKERS="localhost:$KAFKA_PORT" \
TB_FRAUD_METRICS_PORT="$FRAUD_METRICS_PORT" \
  uv run --project "$ROOT/edge/fraud-scoring" fraud-scoring \
  >"${TMPDIR:-/tmp}/tessera-workload-fraud.log" 2>&1 &
PIDS+=("$!")
echo "$!" >"$RUN_DIR/fraud-scoring.pgid"
wait_for "the scorer" "http://localhost:$FRAUD_METRICS_PORT/metrics" 120

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
  --fraud-metrics "http://localhost:$FRAUD_METRICS_PORT/metrics" \
  --proxy-listen ":$LEDGER_PROXY_PORT" \
  --proxy-upstream "http://localhost:$LEDGER_PORT" \
  --run-dir "$RUN_DIR" \
  --broker-container "$KAFKA_CONTAINER" \
  --db-container "$DB_CONTAINER" \
  ${TB_SCENARIO:+--scenario "$TB_SCENARIO"} \
  ${TB_SCENARIO_ID:+--scenario-id "$TB_SCENARIO_ID"} \
  --settle "$SETTLE" \
  --keys "$KEYS" \
  --metrics ":$METRICS_PORT" \
  --manifest "${TB_MANIFEST:-${TMPDIR:-/tmp}/tessera-workload-manifest.json}" \
  ${TB_SCRAPE_DIR:+--scrapes "$TB_SCRAPE_DIR"} \
  "$@" >"$RUN_LOG" 2>&1 &
DRIVER=$!
PIDS+=("$DRIVER")
wait_for_file "the driver wrote its public key" "$KEYS" 120

step "Gateway"
TB_GATEWAY_LEDGER_URL="http://localhost:$LEDGER_PROXY_PORT/v1" \
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

step "Outbox"
# The assertion this fixture most needed and did not have. An unreachable broker and a working one
# produce the same ledger log line, and the only place the difference shows is the age of the oldest
# unpublished row - so WP-23's committed baseline recorded SLO-LEDGER-OUTBOX-FRESHNESS as missed, at
# 140 s against a 60 s target, and that was the fixture rather than the ledger. Nobody noticed,
# because nothing checked. Now something does.
pending=$(ledger_gauge ledger_outbox_pending)
lag=$(ledger_gauge ledger_outbox_lag_seconds)
if [ "${TB_EXPECT_OUTBOX_BACKLOG:-0}" = "1" ]; then
  echo "OK    pending $pending, lag ${lag}s - a backlog this run was told to expect"
elif [ "$pending" = "?" ]; then
  echo "FAIL  the ledger's exposition carries no ledger_outbox_pending, so the relay is unchecked" >&2
  echo "      A missing reading and a reading of zero are different answers, and only one of" >&2
  echo "      them means the relay drained." >&2
  [ "$STATUS" -eq 0 ] && STATUS=1
# Numerically. A Prometheus gauge is a float by specification, so the value is "0.0" and a string
# comparison against "0" fails on a relay that worked perfectly - a control firing on a healthy
# estate, which is worse than no control. Found by running this.
elif awk -v value="$pending" 'BEGIN { exit !(value + 0 == 0) }'; then
  echo "OK    the relay drained everything this run produced; lag ${lag}s"
else
  echo "FAIL  $pending events are still unpublished after ${SETTLE} of settling, oldest ${lag}s old" >&2
  echo "      The ledger cannot reach the broker, or the relay is not running. Every objective this" >&2
  echo "      run reports is a measurement of that rather than of the estate." >&2
  echo "      ledger log  ${TMPDIR:-/tmp}/tessera-workload-ledger.log" >&2
  echo "      broker log  docker logs $KAFKA_CONTAINER" >&2
  [ "$STATUS" -eq 0 ] && STATUS=1
fi

echo
echo "  ledger log   ${TMPDIR:-/tmp}/tessera-workload-ledger.log"
echo "  gateway log  ${TMPDIR:-/tmp}/tessera-workload-gateway.log"
echo "  scorer log   ${TMPDIR:-/tmp}/tessera-workload-fraud.log"
echo "  manifest     ${TB_MANIFEST:-${TMPDIR:-/tmp}/tessera-workload-manifest.json}"

exit "$STATUS"
