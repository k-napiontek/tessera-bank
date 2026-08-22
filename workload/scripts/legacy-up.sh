#!/usr/bin/env bash
#
# Boot stratum 1 - Oracle and a real Tomcat 8.5 with the customer-master WAR on it - and hold it up
# so a driver can put load through it.
#
# **A test fixture, not a component of the bank.** It assembles parts that already exist: the Oracle
# image `test-customer-master` starts through Testcontainers, the schema scripts the WAR itself
# applies, the Tomcat 8.5.100 zip Cargo installs from the Apache archive, and the WAR Maven builds.
# Nothing in `legacy/` changes and no pinned version moves - which is the whole point of driving this
# tier rather than a modernised copy of it.
#
#   bash workload/scripts/legacy-up.sh                    # boot and wait, Ctrl-C to stop
#   bash workload/scripts/legacy-up.sh --accounts 40000   # a larger master
#   bash workload/scripts/legacy-up.sh --keep             # leave it running after this shell exits
#
# **Why the Tomcat is installed rather than containerised.** There is no Tomcat 8.5 image this
# repository already uses, and adding one would be adding infrastructure rather than assembling it.
# What Cargo does inside `CustomerMasterDeploymentIT` is exactly this - fetch the end-of-life zip
# from `archive.apache.org` (an unsupported version is not on a mirror), unpack it, drop the JDBC
# driver into `lib/`, bind the DataSource in the container's own configuration, and deploy the WAR -
# and doing it in shell is what an operations team of 2011 did by hand.
#
# **The WAR carries no connection string**, deliberately: `web.xml` declares a `resource-ref` on
# `jdbc/customerMaster` and the container binds it. That is what lets one artefact deploy to every
# environment unchanged, and it is why the datasource below is written into `context.xml` rather than
# into anything Maven built.
#
# Needs: Docker, a JDK 8, Go, and Maven having built the WAR (`make build-legacy`).

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${TMPDIR:-/tmp}/tessera-legacy"

ORACLE_IMAGE="gvenzl/oracle-free:23-slim-faststart"
ORACLE_CONTAINER="${TB_ORACLE_CONTAINER:-tessera-legacy-oracle}"
ORACLE_PORT="${TB_ORACLE_PORT:-15210}"
ORACLE_USER=tessera
ORACLE_PASSWORD=tessera
ORACLE_SERVICE=FREEPDB1

TOMCAT_VERSION=8.5.100
TOMCAT_URL="https://archive.apache.org/dist/tomcat/tomcat-8/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.zip"
TOMCAT_PORT="${TB_TOMCAT_PORT:-18080}"
TOMCAT_SHUTDOWN_PORT="${TB_TOMCAT_SHUTDOWN_PORT:-18005}"

MODEL="$ROOT/contracts/workload/tessera-day-v1.json"
BUSINESS_DATE=2026-03-02
SEED=42
SCALE=0.0002
CUSTOMERS=2000
ACCOUNTS=0
KEEP=no

usage() { sed -n '3,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --customers) CUSTOMERS="$2"; shift 2 ;;
        --accounts)  ACCOUNTS="$2"; shift 2 ;;
        --date)      BUSINESS_DATE="$2"; shift 2 ;;
        --seed)      SEED="$2"; shift 2 ;;
        --scale)     SCALE="$2"; shift 2 ;;
        --keep)      KEEP=yes; shift ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "legacy-up: unknown argument $1" >&2; exit 2 ;;
    esac
done

WAR="$ROOT/legacy/customer-master/target/customer-master.war"
[ -f "$WAR" ] || { echo "legacy-up: $WAR is not built - run make build-legacy" >&2; exit 1; }

DRIVER_JAR="$(find "${HOME}/.m2/repository/com/oracle/database/jdbc/ojdbc8" -name 'ojdbc8-*.jar' 2>/dev/null | sort | tail -1)"
[ -n "$DRIVER_JAR" ] || { echo "legacy-up: ojdbc8 is not in the local Maven repository - run make build-legacy" >&2; exit 1; }

JAVA8="${JAVA8_HOME:-$(/usr/libexec/java_home -v 1.8 2>/dev/null)}"
[ -n "$JAVA8" ] || { echo "legacy-up: no JDK 8 - see make jdk8" >&2; exit 1; }

TOMCAT_HOME="$WORK/apache-tomcat-$TOMCAT_VERSION"
STOPPED=no

stop() {
    [ "$STOPPED" = yes ] && return
    STOPPED=yes
    echo
    echo "-- stopping"
    if [ -d "$TOMCAT_HOME" ]; then
        JAVA_HOME="$JAVA8" CATALINA_PID="$WORK/tomcat.pid" \
            "$TOMCAT_HOME/bin/catalina.sh" stop 20 -force >/dev/null 2>&1
    fi
    docker rm -f "$ORACLE_CONTAINER" >/dev/null 2>&1
    echo "-- stopped"
}
[ "$KEEP" = yes ] || trap stop EXIT INT TERM

# ---------------------------------------------------------------------------------------------
# Oracle. The same image test-customer-master starts through Testcontainers, on a fixed port so a
# driver started separately can find it.
# ---------------------------------------------------------------------------------------------
echo "-- oracle $ORACLE_IMAGE on $ORACLE_PORT"
docker rm -f "$ORACLE_CONTAINER" >/dev/null 2>&1
docker run -d --name "$ORACLE_CONTAINER" -p "$ORACLE_PORT:1521" \
    -e ORACLE_PASSWORD="$ORACLE_PASSWORD" \
    -e APP_USER="$ORACLE_USER" -e APP_USER_PASSWORD="$ORACLE_PASSWORD" \
    "$ORACLE_IMAGE" >/dev/null || { echo "legacy-up: oracle did not start" >&2; exit 1; }

sqlplus_in() {
    docker exec -i "$ORACLE_CONTAINER" sqlplus -S \
        "$ORACLE_USER/$ORACLE_PASSWORD@//localhost:1521/$ORACLE_SERVICE"
}

# Readiness is asked of the database rather than read off its log. The image does print a banner,
# but it prints it when *it* is finished rather than when the application user can connect, and on a
# cold cache the two are minutes apart - which is a fixture reporting a database that never came up
# while the database sits there answering queries.
printf '   waiting'
READY=no
for _ in $(seq 1 180); do
    if echo 'SELECT 1 FROM dual; EXIT' | sqlplus_in 2>/dev/null | grep -q '^ *1$'; then
        READY=yes
        break
    fi
    printf '.'
    sleep 5
done
[ "$READY" = yes ] && echo " ready" || { echo " oracle never accepted a connection" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# The schema, in the order scripts.list declares. That file is the contract for the order and
# SchemaApplierTest asserts every script on disk appears in it, so reading it here rather than
# globbing is what keeps this fixture and the WAR applying the same schema.
# ---------------------------------------------------------------------------------------------
MIGRATIONS="$ROOT/legacy/customer-master/src/main/resources/db/migration"
echo "-- schema"
while read -r script; do
    case "$script" in ''|\#*) continue ;; esac
    if ! sqlplus_in < "$MIGRATIONS/$script" 2>&1 | grep -qi 'ORA-'; then
        echo "   $script"
    else
        echo "legacy-up: $script failed" >&2
        sqlplus_in < "$MIGRATIONS/$script" 2>&1 | grep -i 'ORA-' | head -5 >&2
        exit 1
    fi
done < "$MIGRATIONS/scripts.list"

# ---------------------------------------------------------------------------------------------
# The population, from the same WP-20 model the online day is drawn from.
# ---------------------------------------------------------------------------------------------
echo "-- population, $CUSTOMERS customers from $(basename "$MODEL")"
mkdir -p "$WORK"
go -C "$ROOT/workload" run ./cmd/workload-dataset \
    --model "$MODEL" --from "$BUSINESS_DATE" --to "$BUSINESS_DATE" \
    --seed "$SEED" --scale "$SCALE" --customers "$CUSTOMERS" 2>/dev/null \
  | go -C "$ROOT/workload" run ./cmd/workload-legacy-seed --accounts "$ACCOUNTS" \
  > "$WORK/seed.sql" || { echo "legacy-up: the seed could not be rendered" >&2; exit 1; }

sqlplus_in < "$WORK/seed.sql" | tail -1

# The references the driver will use, read back out of the database rather than re-derived. A driver
# given references the master does not hold measures the fault path, which is F-18 one stratum up.
sqlplus_in > "$WORK/accounts.txt" <<SQL
SET HEADING OFF PAGESIZE 0 FEEDBACK OFF LINESIZE 200 TRIMSPOOL ON
SELECT account_ref || ' ' || customer_ref FROM account ORDER BY account_ref;
EXIT
SQL
sed -i.bak '/^$/d' "$WORK/accounts.txt" && rm -f "$WORK/accounts.txt.bak"
echo "   $(wc -l < "$WORK/accounts.txt" | tr -d ' ') account references written to $WORK/accounts.txt"

# ---------------------------------------------------------------------------------------------
# Tomcat 8.5. Installed rather than containerised, exactly as Cargo does it inside the deployment
# test - and from the Apache archive, because an end-of-life version is not on a mirror.
# ---------------------------------------------------------------------------------------------
ZIP="$WORK/apache-tomcat-$TOMCAT_VERSION.zip"
if [ ! -f "$ZIP" ]; then
    echo "-- fetching tomcat $TOMCAT_VERSION"
    curl -sSL -o "$ZIP" "$TOMCAT_URL" || { echo "legacy-up: the tomcat zip could not be fetched" >&2; exit 1; }
fi

rm -rf "$TOMCAT_HOME"
unzip -q "$ZIP" -d "$WORK" || { echo "legacy-up: the tomcat zip could not be unpacked" >&2; exit 1; }
chmod +x "$TOMCAT_HOME"/bin/*.sh

# The driver goes into $CATALINA_HOME/lib and not into WEB-INF/lib: ojdbc8 is a provided dependency
# because the container that binds the DataSource is the container that needs it.
cp "$DRIVER_JAR" "$TOMCAT_HOME/lib/"

sed -i.bak \
    -e "s/port=\"8080\"/port=\"$TOMCAT_PORT\"/" \
    -e "s/port=\"8005\"/port=\"$TOMCAT_SHUTDOWN_PORT\"/" \
    "$TOMCAT_HOME/conf/server.xml" && rm -f "$TOMCAT_HOME/conf/server.xml.bak"

# The datasource, bound by the container. maxTotal is deliberately left at Tomcat's own default of 8
# rather than tuned: WP-25's Objective names "a SOAP endpoint whose thread pool is smaller than
# anyone remembers", and a fixture that raises the pool before measuring has answered the question
# by changing it.
cat > "$TOMCAT_HOME/conf/context.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<Context>
  <WatchedResource>WEB-INF/web.xml</WatchedResource>
  <Resource name="jdbc/customerMaster"
            auth="Container"
            type="javax.sql.DataSource"
            driverClassName="oracle.jdbc.OracleDriver"
            url="jdbc:oracle:thin:@//localhost:$ORACLE_PORT/$ORACLE_SERVICE"
            username="$ORACLE_USER"
            password="$ORACLE_PASSWORD"
            validationQuery="SELECT 1 FROM dual"
            testOnBorrow="true"/>
</Context>
XML

cp "$WAR" "$TOMCAT_HOME/webapps/customer-master.war"

echo "-- tomcat $TOMCAT_VERSION on $TOMCAT_PORT, JDK 8"
JAVA_HOME="$JAVA8" CATALINA_PID="$WORK/tomcat.pid" \
    "$TOMCAT_HOME/bin/catalina.sh" start >/dev/null 2>&1

ENDPOINT="http://localhost:$TOMCAT_PORT/customer-master/services/CustomerMasterService"
printf '   waiting'
for _ in $(seq 1 60); do
    if curl -sf -o /dev/null "$ENDPOINT?wsdl"; then
        echo " deployed"
        break
    fi
    printf '.'
    sleep 2
done
curl -sf -o /dev/null "$ENDPOINT?wsdl" || {
    echo " the WAR never answered" >&2
    tail -40 "$TOMCAT_HOME/logs/catalina.out" >&2
    exit 1
}

echo
echo "stratum 1 is up"
echo "   endpoint    $ENDPOINT"
echo "   wsdl        $ENDPOINT?wsdl"
echo "   oracle      localhost:$ORACLE_PORT/$ORACLE_SERVICE as $ORACLE_USER"
echo "   references  $WORK/accounts.txt"
echo "   catalina    $TOMCAT_HOME/logs/catalina.out"

if [ "$KEEP" = yes ]; then
    echo
    echo "left running - stop it with:"
    echo "   JAVA_HOME=$JAVA8 CATALINA_PID=$WORK/tomcat.pid $TOMCAT_HOME/bin/catalina.sh stop 20 -force"
    echo "   docker rm -f $ORACLE_CONTAINER"
    exit 0
fi

echo
echo "Ctrl-C to stop."
while true; do sleep 3600; done
