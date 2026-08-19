#!/usr/bin/env bash
#
# Drive a live estate far enough that the web application's journey can be seen on a screen.
#
# This is the API half of WP-14's Verification. It boots PostgreSQL, the ledger and the gateway,
# mints a token, opens two accounts, funds one, and places a hold so that booked and available
# genuinely diverge - then prints what to do in the browser and leaves everything running. What has
# to be *seen* cannot be scripted; everything leading up to it can, and this makes the manual part
# short enough to actually be performed.
#
#   bash edge/web-banking/scripts/walkthrough.sh
#
# Needs: Docker, a JDK 17, Go, Node. Ctrl-C stops everything it started.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KEYS="${TMPDIR:-/tmp}/tessera-walkthrough-keys.pem"
LEDGER_PORT=8080
GATEWAY_PORT=8081
DEBIT_ACCOUNT="TB90000000000001"
CREDIT_ACCOUNT="TB90000000000002"
FUNDING_ACCOUNT="TB90000000000009"
CUSTOMER="CU0000000001"
PIDS=()

cleanup() {
  echo
  echo "== stopping =="
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
  docker rm -f tessera-walkthrough-db >/dev/null 2>&1 || true
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

step "PostgreSQL"
docker rm -f tessera-walkthrough-db >/dev/null 2>&1 || true
docker run -d --name tessera-walkthrough-db \
  -e POSTGRES_PASSWORD=tessera -e POSTGRES_USER=tessera -e POSTGRES_DB=tessera \
  -p 5433:5432 postgres:16-alpine >/dev/null
for _ in $(seq 60); do
  docker exec tessera-walkthrough-db pg_isready -U tessera >/dev/null 2>&1 && break
  sleep 1
done
echo "OK    PostgreSQL is ready on 5433"

step "Token and key pair"
TOKEN="$(node "$ROOT/edge/web-banking/scripts/dev-token.mjs" --sub "$CUSTOMER" --out "$KEYS")"
echo "OK    token minted, public key at $KEYS"

step "Ledger"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
LEDGER_DB_URL="jdbc:postgresql://localhost:5433/tessera" \
LEDGER_DB_USER=tessera \
LEDGER_DB_PASSWORD=tessera \
  "$ROOT/gradlew" -p "$ROOT" :services:ledger-api:bootRun >"${TMPDIR:-/tmp}/tessera-ledger.log" 2>&1 &
PIDS+=("$!")
wait_for "the ledger" "http://localhost:$LEDGER_PORT/actuator/health/readiness" 180

step "Gateway"
TB_GATEWAY_LEDGER_URL="http://localhost:$LEDGER_PORT/v1" \
TB_GATEWAY_JWT_ISSUER="https://issuer.tesserabank.example" \
TB_GATEWAY_JWT_AUDIENCE="tessera-bank-ledger" \
TB_GATEWAY_JWT_KEYS="$KEYS" \
TB_GATEWAY_LISTEN=":$GATEWAY_PORT" \
TB_GATEWAY_ADMIN_LISTEN=":9091" \
  go -C "$ROOT/edge/api-gateway" run ./cmd/gateway >"${TMPDIR:-/tmp}/tessera-gateway.log" 2>&1 &
PIDS+=("$!")
wait_for "the gateway" "http://localhost:9091/readyz" 60

# The gateway serves the contract's own paths at its root. `/v1` is the *ledger's* prefix, which the
# gateway adds itself from TB_GATEWAY_LEDGER_URL - asking it for /v1/accounts is a `no-route`.
#
# Every response is checked. An earlier version of this script sent each body to /dev/null and
# reported OK for six calls that had all failed with 404, which is the same failure follow-up F-20
# records in the mainframe tier: a check that cannot fail is not a check.
api() {
  local method="$1" path="$2" body="${3:-}" key="${4:-}"
  local args=(-sS -w '\n%{http_code}' -X "$method" -H "Authorization: Bearer $TOKEN" -H "Accept: application/json")
  [ -n "$body" ] && args+=(-H "Content-Type: application/json" -d "$body")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")

  local response status
  response="$(curl "${args[@]}" "http://localhost:$GATEWAY_PORT$path")"
  status="${response##*$'\n'}"
  response="${response%$'\n'*}"

  if [ "$status" -lt 200 ] || [ "$status" -ge 300 ]; then
    echo "FAIL  $method $path answered $status" >&2
    echo "      $response" >&2
    return 1
  fi
  printf '%s' "$response"
}

open_account() {
  api POST /accounts \
    "{\"accountRef\":\"$1\",\"customerRef\":\"$CUSTOMER\",\"accountType\":\"$2\",\"currency\":\"PLN\"}" \
    >/dev/null
  echo "OK    $1 open ($2)"
}

# The account references are fixed, so a second run of this script would open accounts that already
# exist. That is a 409 rather than a failure of the estate, and re-running has to stay cheap.
reset_ledger() {
  docker exec -e PGPASSWORD=tessera tessera-walkthrough-db \
    psql -U tessera -d tessera -c \
    "TRUNCATE audit_record, outbox_record, idempotency_record, posting, journal_entry, hold, balance, account CASCADE" \
    >/dev/null 2>&1 || true
}

step "Accounts"
reset_ledger
open_account "$FUNDING_ACCOUNT" ASSET
open_account "$DEBIT_ACCOUNT" LIABILITY
open_account "$CREDIT_ACCOUNT" LIABILITY

step "Funding"
api POST /transfers \
  "{\"debitAccountRef\":\"$FUNDING_ACCOUNT\",\"creditAccountRef\":\"$DEBIT_ACCOUNT\",\"amount\":{\"amountMinor\":500000,\"currency\":\"PLN\"},\"reference\":\"opening\"}" \
  "walkthrough-funding-0000001" >/dev/null
echo "OK    5000.00 PLN credited to $DEBIT_ACCOUNT"

step "A hold, so booked and available diverge"
api POST "/accounts/$DEBIT_ACCOUNT/holds" \
  "{\"amount\":{\"amountMinor\":60000,\"currency\":\"PLN\"}}" \
  "walkthrough-hold-0000000001" >/dev/null
echo "OK    600.00 PLN held"

step "Balances as the estate sees them"
api GET "/accounts/$DEBIT_ACCOUNT/balance"
echo

step "Now look at it"
cat <<INSTRUCTIONS

The estate is up. In another terminal:

  VITE_GATEWAY_URL=http://localhost:$GATEWAY_PORT npm --prefix edge/web-banking run dev

Sign in with these:

  token             $TOKEN
  account refs      $DEBIT_ACCOUNT $CREDIT_ACCOUNT

Then check, in order:

  1. The card for $DEBIT_ACCOUNT shows booked 5000.00 and available 4400.00, and says 600.00 PLN
     is held. Two figures, not one.
  2. Transfer 12.34 to $CREDIT_ACCOUNT. Both balances move by 12.34.
  3. Stop the gateway (Ctrl-C in its terminal) and send another transfer. It must present as
     "Not yet known", never as sent and never as failed.
  4. Start the gateway again and press "Check again". It resolves to one transfer.
  5. Confirm with:

       curl -H "Authorization: Bearer \$TOKEN" \\
         "http://localhost:$GATEWAY_PORT/accounts/$CREDIT_ACCOUNT/statement?from=2020-01-01&to=2030-01-01"

     Exactly one movement for the amount in step 3, not two.

Ctrl-C here stops the ledger, the gateway and PostgreSQL.
INSTRUCTIONS

wait
