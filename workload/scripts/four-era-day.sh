#!/usr/bin/env bash
#
# One bank day driven through all four eras at once: Kafka in, canonical XML by XSLT, SOAP to
# Tomcat 8.5, COMP-3 movement record out.
#
# **A test fixture, not a component of the bank.** It composes what already exists rather than
# reimplementing any of it, the way soak.sh composes the scripts either side of it and migration.sh
# runs a second process beside the day: legacy-up.sh boots Oracle and Tomcat 8.5 with the
# customer-master WAR, estate-up.sh boots PostgreSQL, Kafka, the ledger, the scorer and the gateway
# and drives the day, and this starts integration/esb-adapter between the two. Nothing in legacy/,
# integration/, services/ or mainframe/ changes and no pinned version moves.
#
#   bash workload/scripts/four-era-day.sh
#   bash workload/scripts/four-era-day.sh --customers 8000 --compress 720 --bound 20m
#
# **FourEraTransferIT does this once, with one transfer.** This does it with a day's worth, and the
# question it asks is the one a single transfer cannot answer: *where does the backlog form when the
# tier below is slower than the tier above?*
#
# **The events are the ledger's own.** Driving the modern spine makes the outbox relay publish, and
# the adapter consumes what a real day produced rather than messages a driver injected. That matters
# for the figures: the relay ships at most LEDGER_OUTBOX_BATCH rows every LEDGER_OUTBOX_INTERVAL_MS,
# both fixed in wall clock, so whatever the compression dial says the adapter is offered about 200
# events a second and no more. That is F-84, and it is the ceiling on the *input* rather than on the
# hop.
#
# **Nothing is instrumented to make this measurable.** esb-adapter has no actuator, no Micrometer and
# no web starter, and adding one would be modernising a Boot 2.7 component to make it observable -
# which is what WP-24's Constraint refused and F-85 recorded instead. So the hop is watched from
# outside, exactly as WP-25b watched stratum 1: the broker's own consumer-group listing, the movement
# file's length, and the two INFO lines per transfer the adapter already writes for operators.
#
# **The broker outlives the day on purpose.** A consumer slower than the day it is fed drains after
# the driver has stopped, so TB_KEEP_BROKER=1 keeps Kafka up past estate-up.sh's own teardown and
# this script removes it below. Without it the drain - the interesting half - could not be sampled
# at all.
#
# **A backlog that does not drain is the measurement, not a failure.** The ledger posts around 790
# movements a second and this hop is single-threaded end to end: one partition, one consumer thread,
# a synchronous SOAP call, and a movement-file append that scans every record already in the file.
# If the bound expires with events still unread, that is the finding and the report says so.
#
# Needs: Docker, a JDK 8, a JDK 17, Go, uv, and the WAR and the adapter jar built
# (`make build-legacy build-integration`).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${TB_FOUR_ERA_OUT:-$ROOT/workload/baselines/four-era}"
WORK="${TMPDIR:-/tmp}/tessera-four-era"

MODEL="$ROOT/contracts/workload/tessera-day-v1.json"
BUSINESS_DATE=2026-03-02
CUSTOMERS=8000
SCALE=0.002
COMPRESS=720
WINDOW=branch-hours
SEED=42
BOUND=20m
INTERVAL=2s

# Its own database and broker, so a four-era run never lands in the ones baseline.sh, soak.sh or the
# signature sweep are holding. Stratum 1 keeps legacy-up.sh's own ports, because nothing else in this
# repository boots stratum 1 at the same time and the endpoint it prints is the one to point at.
DB_CONTAINER=tessera-fourera-db
DB_PORT=5437
KAFKA_CONTAINER=tessera-fourera-kafka
KAFKA_PORT=9095
TOMCAT_PORT=18080

TOPIC=tessera.ledger.transfer-posted.v1
DEAD_LETTER_TOPIC=tessera.esb.transfer-posted.dlt.v1
GROUP=esb-adapter

usage() { sed -n '3,44p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --customers) CUSTOMERS="$2"; shift 2 ;;
        --scale)     SCALE="$2"; shift 2 ;;
        --compress)  COMPRESS="$2"; shift 2 ;;
        --window)    WINDOW="$2"; shift 2 ;;
        --date)      BUSINESS_DATE="$2"; shift 2 ;;
        --seed)      SEED="$2"; shift 2 ;;
        --bound)     BOUND="$2"; shift 2 ;;
        --interval)  INTERVAL="$2"; shift 2 ;;
        --out)       OUT="$2"; shift 2 ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "four-era-day: unknown argument $1" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# -------------------------------------------------------------------------------------------------
# Pre-flight. Everything this run needs, checked before anything is booted - and the containers that
# would compete for Docker's memory are *named* rather than removed. This fixture starts four
# containers and four processes across two JDKs; it is the heaviest thing in this repository, and
# killing somebody else's cluster to make room for it is not a fixture's decision to take.
# -------------------------------------------------------------------------------------------------
step "Pre-flight"

WAR="$ROOT/legacy/customer-master/target/customer-master.war"
[ -f "$WAR" ] || { echo "four-era-day: $WAR is not built - run make build-legacy" >&2; exit 1; }

ADAPTER_JAR="$(find "$ROOT/integration/esb-adapter/target" -maxdepth 1 -name 'esb-adapter-*.jar' 2>/dev/null | sort | tail -1)"
[ -n "$ADAPTER_JAR" ] || { echo "four-era-day: the adapter jar is not built - run make build-integration" >&2; exit 1; }

JAVA8="${JAVA8_HOME:-$(/usr/libexec/java_home -v 1.8 2>/dev/null)}"
[ -n "$JAVA8" ] || { echo "four-era-day: no JDK 8 - see make jdk8" >&2; exit 1; }
command -v uv >/dev/null || { echo "four-era-day: uv is not installed" >&2; exit 1; }

# Containers this fixture does not own, holding memory it is about to need. Oracle alone wants about
# 2 GiB and Docker Desktop here is commonly given 8.
COMPETING="$(docker ps --format '{{.Names}}' 2>/dev/null \
  | grep -v -E "^(${DB_CONTAINER}|${KAFKA_CONTAINER}|tessera-legacy-oracle)$" || true)"
if [ -n "$COMPETING" ]; then
    echo "four-era-day: these containers are running and this fixture needs the memory:" >&2
    echo "$COMPETING" | sed 's/^/    /' >&2
    echo "  Stop them and run again. This script will not remove a container it did not start." >&2
    exit 1
fi
echo "OK    nothing else is holding Docker"
echo "OK    the WAR, the adapter jar and both JDKs are present"

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"

MOVEMENT_FILE="$WORK/MOVEMENT.DAT"
ADAPTER_LOG="$WORK/adapter.log"
ADAPTER_PID=""
HOP_PID=""

stop() {
    echo
    echo "== stopping =="
    [ -n "$HOP_PID" ] && kill "$HOP_PID" 2>/dev/null || true
    if [ -n "$ADAPTER_PID" ]; then
        kill "$ADAPTER_PID" 2>/dev/null || true
        for _ in $(seq 20); do kill -0 "$ADAPTER_PID" 2>/dev/null || break; sleep 1; done
    fi
    # Exactly the two commands legacy-up.sh --keep prints, because it is --keep that hands the
    # teardown to whoever composed it.
    # Found rather than named: legacy-up.sh pins the Tomcat version and a second copy of that pin
    # here would be one more thing to keep in step with a stratum nothing is allowed to upgrade.
    LEGACY_WORK="${TMPDIR:-/tmp}/tessera-legacy"
    CATALINA="$(find "$LEGACY_WORK" -maxdepth 2 -type f -path '*/bin/catalina.sh' 2>/dev/null | head -1)"
    if [ -n "$CATALINA" ]; then
        JAVA_HOME="$JAVA8" CATALINA_PID="$LEGACY_WORK/tomcat.pid" \
            "$CATALINA" stop 20 -force >/dev/null 2>&1 || true
    fi
    docker rm -f "${TB_ORACLE_CONTAINER:-tessera-legacy-oracle}" >/dev/null 2>&1 || true
    docker rm -f "$KAFKA_CONTAINER" "$DB_CONTAINER" >/dev/null 2>&1 || true
    echo "-- stopped"
}
trap stop EXIT INT TERM

step "Plan"
echo "  business date  $BUSINESS_DATE"
echo "  population     $CUSTOMERS customers, $(( CUSTOMERS * 2 + 1 )) accounts"
echo "  dials          scale $SCALE, ${COMPRESS}x, $WINDOW, seed $SEED"
echo "  the hop        Kafka -> XSLT -> SOAP on :$TOMCAT_PORT -> COMP-3 in $MOVEMENT_FILE"
echo "  bounded at     $BOUND, sampled every $INTERVAL"
echo "  output         $OUT"

# -------------------------------------------------------------------------------------------------
# Stratum 1, first and on its own, because it is the slowest thing here to boot and because both
# halves have to agree about which accounts exist. They do: the population a stream declares is
# derived from the model and the customer count and carries no seed at all, so legacy-up.sh's
# --customers and estate-up.sh's open exactly the same account set. A driver posting against
# references the master does not hold measures the fault path, which is F-18 one stratum up.
# -------------------------------------------------------------------------------------------------
step "Stratum 1: Oracle and Tomcat 8.5"
bash "$ROOT/workload/scripts/legacy-up.sh" --keep \
    --customers "$CUSTOMERS" --date "$BUSINESS_DATE" --seed "$SEED" \
    2>&1 | tee "$WORK/legacy-up.log" | grep -E "^(--|OK|   |stratum)" || true

ENDPOINT="http://localhost:$TOMCAT_PORT/customer-master/services/CustomerMasterService"
curl -sf -o /dev/null "$ENDPOINT?wsdl" \
    || { echo "four-era-day: stratum 1 did not come up - see $WORK/legacy-up.log" >&2; exit 1; }
echo "OK    the WSDL answers on $TOMCAT_PORT"

# -------------------------------------------------------------------------------------------------
# Stratum 2 and the watcher, brought up beside the day rather than before it. estate-up.sh boots the
# broker itself as part of driving the day, so this waits for it - the same shape migration.sh uses,
# where the exercise is started first and waits for the driver to announce the run.
#
# The adapter reads from the beginning of the topic (auto-offset-reset: earliest, and never latest -
# a consumer that skipped what accumulated while it was down would leave the mainframe short by
# exactly the transfers nobody saw), so joining a few seconds into the day loses nothing. What it
# does produce is initial lag, and the sampler records it rather than hiding it.
# -------------------------------------------------------------------------------------------------
bring_up_stratum_2_and_watch() {
    for _ in $(seq 180); do
        if docker exec "$KAFKA_CONTAINER" \
            kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done

    # Created here rather than left to auto-creation, for the reason estate-up.sh already documents
    # about the scorer's topic: a producer creates a topic on first send, and DeadLetterRecorder
    # never awaits its send, so the first dead letter of a run could be lost to a topic that did not
    # exist yet and nothing would say so.
    docker exec "$KAFKA_CONTAINER" kafka-topics --bootstrap-server localhost:9092 \
        --create --if-not-exists --topic "$DEAD_LETTER_TOPIC" \
        --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true

    # Every value from the environment, which is the line between extending the fixture and
    # modifying the estate. The contracts directory has to be absolute: the default is relative and
    # the WSDL imports the canonical schema by a path beside it. The endpoint has to be given: the
    # default is :8080, which under estate-up.sh is the *ledger's* port.
    JAVA_HOME="$JAVA8" nohup "$JAVA8/bin/java" -jar "$ADAPTER_JAR" \
        --spring.kafka.bootstrap-servers="localhost:$KAFKA_PORT" \
        --tessera.esb.customer-master-endpoint="$ENDPOINT" \
        --tessera.contracts.dir="$ROOT/contracts" \
        --tessera.esb.movement-file="$MOVEMENT_FILE" \
        >"$ADAPTER_LOG" 2>&1 &
    echo $! >"$WORK/adapter.pid"

    # Readiness asked of the broker rather than read off a log, the same rule legacy-up.sh applies
    # to Oracle: the group is up when something holds a partition of the topic, and a log line saying
    # "Started EsbAdapterApplication" is printed before that is true.
    for _ in $(seq 120); do
        if docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
            --bootstrap-server localhost:9092 --describe --group "$GROUP" 2>/dev/null \
            | grep -q "$TOPIC"; then
            break
        fi
        sleep 1
    done

    go -C "$ROOT/workload" run ./cmd/workload-hop \
        --broker-container "$KAFKA_CONTAINER" \
        --group "$GROUP" --dead-letter-topic "$DEAD_LETTER_TOPIC" \
        --movement-file "$MOVEMENT_FILE" --adapter-log "$ADAPTER_LOG" \
        --interval "$INTERVAL" --bound "$BOUND" \
        --endpoint "$ENDPOINT" --business-date "$BUSINESS_DATE" \
        --customers "$CUSTOMERS" --accounts "$(( CUSTOMERS * 2 + 1 ))" \
        --scale "$SCALE" --compress "$COMPRESS" --seed "$SEED" \
        --partitions 1 --listener-concurrency 1 \
        --relay-batch 100 --relay-interval-ms 500 \
        --report "$OUT/hop.json" --out "$OUT/hop.txt"
}

step "Stratum 2 and the watcher, beside the day"
bring_up_stratum_2_and_watch >"$WORK/hop.log" 2>&1 &
WATCHER=$!

# -------------------------------------------------------------------------------------------------
# The online day, against the live estate. TB_KEEP_BROKER keeps Kafka up past this script's own
# teardown so the drain can be sampled after the driver stops.
# -------------------------------------------------------------------------------------------------
step "The online day"
set +e
TB_DB_PORT="$DB_PORT" \
TB_DB_CONTAINER="$DB_CONTAINER" \
TB_KAFKA_PORT="$KAFKA_PORT" \
TB_KAFKA_CONTAINER="$KAFKA_CONTAINER" \
TB_KEEP_BROKER=1 \
TB_MANIFEST="$WORK/run-manifest.json" \
TB_SCRAPE_DIR="$WORK" \
    bash "$ROOT/workload/scripts/estate-up.sh" \
        --date "$BUSINESS_DATE" --seed "$SEED" --scale "$SCALE" --compress "$COMPRESS" \
        --window "$WINDOW" --customers "$CUSTOMERS" --seed-population --require-postings \
    >"$WORK/online.log" 2>&1
day_status=$?
set -e

grep -E "^  (scheduled|opened|posted|peak)|^OK    the relay|published the run" "$WORK/online.log" || true
if [ "$day_status" -ne 0 ]; then
    echo "FAIL  the online day exited $day_status - see $WORK/online.log" >&2
    tail -30 "$WORK/online.log" >&2
    exit 1
fi

# The relay drained before estate-up.sh exited - it asserts ledger_outbox_pending is zero - so
# everything the ledger produced is on the topic by now. Whatever lag the adapter still carries from
# here is the adapter's own backlog and nothing else's, which is what makes the drain below readable.
step "The drain, after the day has stopped"
ADAPTER_PID="$(cat "$WORK/adapter.pid" 2>/dev/null || true)"
wait "$WATCHER" || true
cat "$WORK/hop.log"

# -------------------------------------------------------------------------------------------------
# WP-11b's constraint, under load for the first time: nothing is written to the movement file unless
# the SOAP call succeeded. A record in the file with no transfer behind it in the system of record is
# 1995 believing a payment that 2011 never accepted - exactly the break batch/recon exists to find.
# -------------------------------------------------------------------------------------------------
step "The constraint: nothing in the file that the system of record refused"
bash "$ROOT/workload/scripts/movement-file-check.sh" \
    --movement-file "$MOVEMENT_FILE" \
    --oracle-container "${TB_ORACLE_CONTAINER:-tessera-legacy-oracle}" \
    | tee "$OUT/constraint.txt"

step "Done"
cp "$ADAPTER_LOG" "$OUT/adapter.log" 2>/dev/null || true
cp "$WORK/online.log" "$WORK/run-manifest.json" "$OUT/" 2>/dev/null || true
echo "  captures under $OUT"
