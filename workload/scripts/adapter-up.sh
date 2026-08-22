#!/usr/bin/env bash
#
# Start stratum 2 - integration/esb-adapter on JDK 8 - against a broker and a stratum-1 endpoint that
# are already up, and return once the broker says it holds a partition.
#
# **A test fixture, not a component of the bank.** It starts the adapter's own jar and gives it every
# value from the environment, which is the line between extending the fixture and modifying the
# estate. Nothing in `integration/` changes and no pinned version moves.
#
#   bash workload/scripts/adapter-up.sh \
#     --broker-container tessera-fourera-kafka --bootstrap localhost:9094 \
#     --kafka-host localhost:9095 \
#     --endpoint http://localhost:18080/customer-master/services/CustomerMasterService \
#     --movement-file /tmp/x/MOVEMENT.DAT --log /tmp/x/adapter.log --pid-file /tmp/x/adapter.pid
#
# **The adapter outlives this script.** It is started with nohup and its pid written where the caller
# asked, because a fixture that composes several scripts needs the process to survive the one that
# started it - the same reason legacy-up.sh has --keep.
#
# **Two addresses, and they are not interchangeable.** --kafka-host is what the *adapter* connects to
# from the host; --bootstrap is the broker's INTERNAL listener, which is what the broker's own tools
# must use from inside the container. estate-up.sh advertises PLAINTEXT as the host port, so a tool
# run in the container is told to connect to a port nothing there is listening on. That cost WP-25d a
# whole run.
#
# Needs: Docker, a JDK 8, and the adapter jar built (`make build-integration`).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

BROKER_CONTAINER=tessera-fourera-kafka
BOOTSTRAP=localhost:9094
KAFKA_HOST=localhost:9095
ENDPOINT=
MOVEMENT_FILE=
LOG=
PID_FILE=
GROUP=esb-adapter
TOPIC=tessera.ledger.transfer-posted.v1
DEAD_LETTER_TOPIC=tessera.esb.transfer-posted.dlt.v1

usage() { sed -n '3,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --broker-container)  BROKER_CONTAINER="$2"; shift 2 ;;
        --bootstrap)         BOOTSTRAP="$2"; shift 2 ;;
        --kafka-host)        KAFKA_HOST="$2"; shift 2 ;;
        --endpoint)          ENDPOINT="$2"; shift 2 ;;
        --movement-file)     MOVEMENT_FILE="$2"; shift 2 ;;
        --log)               LOG="$2"; shift 2 ;;
        --pid-file)          PID_FILE="$2"; shift 2 ;;
        --group)             GROUP="$2"; shift 2 ;;
        --topic)             TOPIC="$2"; shift 2 ;;
        --dead-letter-topic) DEAD_LETTER_TOPIC="$2"; shift 2 ;;
        -h|--help)           usage; exit 0 ;;
        *) echo "adapter-up: unknown argument $1" >&2; exit 2 ;;
    esac
done

for required in ENDPOINT MOVEMENT_FILE LOG PID_FILE; do
    [ -n "${!required}" ] || { echo "adapter-up: --${required,,} is required" >&2; exit 2; }
done

ADAPTER_JAR="$(find "$ROOT/integration/esb-adapter/target" -maxdepth 1 -name 'esb-adapter-*.jar' 2>/dev/null | sort | tail -1)"
[ -n "$ADAPTER_JAR" ] || { echo "adapter-up: the adapter jar is not built - run make build-integration" >&2; exit 1; }

JAVA8="${JAVA8_HOME:-$(/usr/libexec/java_home -v 1.8 2>/dev/null)}"
[ -n "$JAVA8" ] || { echo "adapter-up: no JDK 8 - see make jdk8" >&2; exit 1; }

# The broker, which estate-up.sh boots as part of driving a day, so this waits rather than assuming.
for _ in $(seq 180); do
    if docker exec "$BROKER_CONTAINER" \
        kafka-broker-api-versions --bootstrap-server "$BOOTSTRAP" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

# Created here rather than left to auto-creation, for the reason estate-up.sh already documents about
# the scorer's topic: a producer creates a topic on first send, and DeadLetterRecorder never awaits
# its send, so the first dead letter of a run could be lost to a topic that did not exist yet and
# nothing would say so.
docker exec "$BROKER_CONTAINER" kafka-topics --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists --topic "$DEAD_LETTER_TOPIC" \
    --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true

# The contracts directory has to be absolute: the default is relative and the WSDL imports the
# canonical schema by a path beside it. The endpoint has to be given: the default is :8080, which
# under estate-up.sh is the ledger's port.
JAVA_HOME="$JAVA8" nohup "$JAVA8/bin/java" -jar "$ADAPTER_JAR" \
    --spring.kafka.bootstrap-servers="$KAFKA_HOST" \
    --tessera.esb.customer-master-endpoint="$ENDPOINT" \
    --tessera.contracts.dir="$ROOT/contracts" \
    --tessera.esb.movement-file="$MOVEMENT_FILE" \
    >"$LOG" 2>&1 &
echo $! >"$PID_FILE"

# Readiness asked of the broker rather than read off a log, the same rule legacy-up.sh applies to
# Oracle: the group is up when something holds a partition of the topic, and "Started
# EsbAdapterApplication" is printed before that is true.
#
# **It fails rather than falling through.** On WP-25d's second run this loop was asking on the wrong
# listener, so every attempt hung retrying for about seven seconds and 120 of them took thirteen and
# a half minutes - after which the loop simply ended and sampling started against a group nothing had
# confirmed was there. A readiness probe that gives up quietly is worse than no probe: it turns a
# broken fixture into a run that produces plausible-looking output.
subscribed=no
for _ in $(seq 60); do
    if docker exec "$BROKER_CONTAINER" kafka-consumer-groups \
        --bootstrap-server "$BOOTSTRAP" --describe --group "$GROUP" 2>/dev/null \
        | grep -q "$TOPIC"; then
        subscribed=yes
        break
    fi
    sleep 2
done
if [ "$subscribed" != yes ]; then
    echo "adapter-up: the adapter never joined group $GROUP on $TOPIC." >&2
    echo "  The broker's own listing is what was asked, on $BOOTSTRAP." >&2
    echo "  See $LOG." >&2
    exit 1
fi
echo "OK    the adapter holds a partition of $TOPIC"
