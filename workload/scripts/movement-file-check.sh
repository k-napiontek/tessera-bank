#!/usr/bin/env bash
#
# WP-11b's constraint, checked against a whole day rather than against one transfer:
#
#   **Nothing is written to the movement file unless the SOAP call succeeded.**
#
# TransferBridge enforces it structurally - notifyTransferPosted is called before movementFile.append
# and any throw exits before the append is reached - and three unit tests pin it. What none of them
# can show is whether it holds when 12 000 transfers cross under load, with redeliveries, faults and
# a partition that blocks. This is that check.
#
#   bash workload/scripts/movement-file-check.sh --movement-file /tmp/.../MOVEMENT.DAT
#
# **The direction is the one that matters.** A transfer the system of record holds but the file does
# not is recoverable: the message is redelivered, the far end answers alreadyApplied, and the writer
# asks the *file* rather than the answer, so it completes what was missing - that is ADR 0014, and
# TransferBridgeTest pins it. The other direction is not recoverable. A record in the file with no
# transfer behind it in the system of record is 1995 believing a payment 2011 never accepted, and the
# two halves of the estate then disagree for ever about money that never moved. That is precisely the
# break batch/recon exists to find, and it is what this asserts is absent.
#
# Reads only, from both sides.

set -euo pipefail

MOVEMENT_FILE=""
ORACLE_CONTAINER="${TB_ORACLE_CONTAINER:-tessera-legacy-oracle}"
ORACLE_USER=tessera
ORACLE_PASSWORD=tessera
ORACLE_SERVICE=FREEPDB1

# MOVEREC, per contracts/copybook/MOVEREC.CPY: MOV-TRANSFER-REF is the first twenty bytes of every
# hundred-and-twenty. A seek over the key field, never a search for the text anywhere - a remittance
# reference is free text and may perfectly well quote a transfer reference.
RECORD_LENGTH=120
REF_LENGTH=20

usage() { sed -n '3,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --movement-file)    MOVEMENT_FILE="$2"; shift 2 ;;
        --oracle-container) ORACLE_CONTAINER="$2"; shift 2 ;;
        -h|--help)          usage; exit 0 ;;
        *) echo "movement-file-check: unknown argument $1" >&2; exit 2 ;;
    esac
done

[ -n "$MOVEMENT_FILE" ] || { echo "movement-file-check: --movement-file is required" >&2; exit 2; }
[ -f "$MOVEMENT_FILE" ] || { echo "movement-file-check: $MOVEMENT_FILE does not exist" >&2; exit 1; }

SIZE=$(wc -c <"$MOVEMENT_FILE" | tr -d ' ')
if [ $(( SIZE % RECORD_LENGTH )) -ne 0 ]; then
    # sortrec.py abends STEP010 with RC 12 on a file that is not a whole number of records, so this
    # fails here where the cause is visible rather than in another tier in the middle of the night.
    echo "FAIL  $MOVEMENT_FILE is $SIZE bytes, which is not a whole number of $RECORD_LENGTH-byte records" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Every distinct transfer reference in the file, read off the key field of each record.
python3 - "$MOVEMENT_FILE" "$RECORD_LENGTH" "$REF_LENGTH" >"$WORK/in-file.txt" <<'PY'
import sys

path, record_length, ref_length = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
seen = set()
with open(path, "rb") as movements:
    while True:
        record = movements.read(record_length)
        if len(record) < record_length:
            break
        seen.add(record[:ref_length].decode("ascii", "replace").strip())
for ref in sorted(seen):
    print(ref)
PY

IN_FILE=$(wc -l <"$WORK/in-file.txt" | tr -d ' ')
RECORDS=$(( SIZE / RECORD_LENGTH ))

# What the system of record accepted. applied_transfer is the table pkg_posting inserts into, and its
# primary key on transfer_ref is what makes NotifyTransferPosted idempotent in the first place.
docker exec -i "$ORACLE_CONTAINER" sqlplus -S \
    "$ORACLE_USER/$ORACLE_PASSWORD@//localhost:1521/$ORACLE_SERVICE" >"$WORK/applied.txt" <<'SQL'
SET HEADING OFF PAGESIZE 0 FEEDBACK OFF LINESIZE 200 TRIMSPOOL ON
SELECT transfer_ref FROM applied_transfer ORDER BY transfer_ref;
EXIT
SQL
sed -i.bak '/^[[:space:]]*$/d' "$WORK/applied.txt" && rm -f "$WORK/applied.txt.bak"
sed -i.bak 's/[[:space:]]*$//' "$WORK/applied.txt" && rm -f "$WORK/applied.txt.bak"

APPLIED=$(wc -l <"$WORK/applied.txt" | tr -d ' ')

sort -o "$WORK/in-file.txt" "$WORK/in-file.txt"
sort -o "$WORK/applied.txt" "$WORK/applied.txt"

# In the file and not in the system of record. This is the set that must be empty.
comm -23 "$WORK/in-file.txt" "$WORK/applied.txt" >"$WORK/unbacked.txt"
UNBACKED=$(wc -l <"$WORK/unbacked.txt" | tr -d ' ')

# Accepted by the system of record and not yet in the file. Expected and recoverable - it is what a
# transfer in flight when the run stopped looks like - so it is reported rather than failed on.
comm -13 "$WORK/in-file.txt" "$WORK/applied.txt" >"$WORK/not-yet-written.txt"
PENDING=$(wc -l <"$WORK/not-yet-written.txt" | tr -d ' ')

echo "== The constraint under load =="
echo
echo "  movement records                    $RECORDS"
echo "  distinct transfers in the file      $IN_FILE"
echo "  transfers the system of record has  $APPLIED"
echo "  in the file, not in the master      $UNBACKED"
echo "  in the master, not yet in the file  $PENDING"
echo

if [ "$UNBACKED" -ne 0 ]; then
    echo "FAIL  $UNBACKED transfers are in the movement file that the system of record never accepted."
    echo "      Tonight's cycle would post money 2011 refused. The first ten:"
    head -10 "$WORK/unbacked.txt" | sed 's/^/        /'
    exit 1
fi

echo "OK    every transfer in the movement file was accepted by the system of record first."
if [ "$PENDING" -ne 0 ]; then
    echo "      $PENDING went the other way - accepted, not yet written. That direction recovers:"
    echo "      the message is redelivered, the far end answers alreadyApplied, and the writer asks"
    echo "      the file rather than the answer, so it completes what was missing (ADR 0014)."
fi
