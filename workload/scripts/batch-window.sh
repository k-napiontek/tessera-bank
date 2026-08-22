#!/usr/bin/env bash
#
# Time the overnight cycle at three volumes, per step, against a movement file the WP-20 model drew.
#
# **A test fixture, not a component of the bank.** It composes what already exists rather than
# reimplementing any of it: workload-dataset draws the day, mainframe/data/generate.py writes it as
# stratum-0 files, and mainframe/jcl/run-eod.sh runs the cycle. Nothing here changes the cycle.
#
#   bash workload/scripts/batch-window.sh
#   bash workload/scripts/batch-window.sh --volumes 200000:0.02 600000:0.06 1200000:0.2
#
# A volume is customers:scale. Both dials move together because both halves of the cycle scale with
# a different one: the master is the customer count and the movement file is the scale, and a bigger
# bank has more of each. Moving one alone measures half the window.
#
# **Per step, not one total, and for two reasons.** STEP020 is ACCTPOST, which match-merges two
# sorted files in one pass and never holds the master in memory - that is the property stratum 0
# exists to demonstrate, and CLAUDE.md keeps a trap entry about the version that loads it. STEP010
# and STEP030 call sortrec.py, whose own docstring says it "reads the whole file into a list" and
# that "nothing here should be read as evidence that the local cycle handles a master larger than
# memory". DFSORT spills to work datasets; this stand-in does not. A single total would mix the
# tier's real property with a local stand-in's ceiling and answer neither question.
#
# **What the volumes are chosen for.** Three points far enough apart to show a shape, and small
# enough that the sort stand-in is not the whole measurement. The report states where its share of
# the window starts to dominate rather than leaving it to be inferred.
#
# Needs: Go, GnuCOBOL, python3. No Docker, no database - stratum 0 is driven by files and files only.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${TB_BATCH_OUT:-$ROOT/workload/baselines/batch-window}"
WORK="${TMPDIR:-/tmp}/tessera-batch-window"

MODEL="$ROOT/contracts/workload/tessera-day-v1.json"
BUSINESS_DATE=2026-03-02
SEED=42
VOLUMES=(200000:0.02 600000:0.06 1200000:0.2)

usage() {
    sed -n '3,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --volumes)   shift; VOLUMES=(); while [ $# -gt 0 ] && [ "${1#-}" = "$1" ]; do VOLUMES+=("$1"); shift; done ;;
        --date)      BUSINESS_DATE="$2"; shift 2 ;;
        --seed)      SEED="$2"; shift 2 ;;
        --out)       OUT="$2"; shift 2 ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "batch-window: unknown argument $1" >&2; exit 2 ;;
    esac
done

command -v cobc >/dev/null || { echo "batch-window: GnuCOBOL is not installed - brew install gnucobol" >&2; exit 1; }

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"

DATE_COMPACT="${BUSINESS_DATE//-/}"
REPORT="$OUT/report.txt"

{
    echo "== The batch window =="
    echo "  business date $BUSINESS_DATE, model $(basename "$MODEL"), seed $SEED"
    echo "  $(uname -s | tr '[:upper:]' '[:lower:]') $(uname -m), $(sysctl -n hw.ncpu 2>/dev/null || nproc) cores, commit $(git -C "$ROOT" rev-parse --short HEAD)"
    echo
} > "$REPORT"

for volume in "${VOLUMES[@]}"; do
    customers="${volume%%:*}"
    scale="${volume##*:}"
    run="$WORK/$customers"
    mkdir -p "$run"

    echo "-- drawing $customers customers at scale $scale"
    python3 "$ROOT/workload/scripts/run-with-rss.py" -- \
        bash -c "go -C '$ROOT/workload' run ./cmd/workload-dataset \
            --model '$MODEL' --from '$BUSINESS_DATE' --to '$BUSINESS_DATE' \
            --seed '$SEED' --scale '$scale' --customers '$customers' 2>'$run/dataset.err' \
            | python3 '$ROOT/mainframe/data/generate.py' --from-stream --out '$run'" \
        > "$run/generate.txt" 2>"$run/generate-rss.txt"
    cat "$run/generate.txt"

    accounts=$(( $(wc -c < "$run/ACCTMAST.DAT") / 100 ))
    movements=$(( $(wc -c < "$run/MOVEMENT.DAT") / 120 ))

    echo "-- running the cycle over $movements movements against $accounts accounts"
    python3 "$ROOT/workload/scripts/run-with-rss.py" -- \
        bash "$ROOT/mainframe/jcl/run-eod.sh" \
        --business-date "$DATE_COMPACT" \
        --master "$run/ACCTMAST.DAT" \
        --movements "$run/MOVEMENT.DAT" \
        --work "$run/eod" > "$run/cycle.txt" 2>"$run/rss.txt" || {
            cat "$run/rss.txt" >&2
            echo "batch-window: the cycle abended at $customers customers" >&2
            tail -20 "$run/cycle.txt" >&2
            exit 1
        }

    applied=$(awk '/MOVE-APPLIED/  { gsub(",", "", $4); print $4 }' "$run/cycle.txt")
    rejected=$(awk '/MOVE-REJECTED/ { gsub(",", "", $4); print $4 }' "$run/cycle.txt")
    substituted=$(awk '/currency substituted/ { print $3 }' "$run/generate.txt")
    rss=$(awk '/RSS-PEAK-BYTES/ { printf "%.2f", $2 / 1073741824 }' "$run/rss.txt")
    writer_rss=$(awk '/RSS-PEAK-BYTES/ { printf "%.2f", $2 / 1073741824 }' "$run/generate-rss.txt")

    {
        echo "-- $accounts accounts, $movements movements   (customers $customers, scale $scale)"
        echo "   applied $applied, rejected $rejected, currency substituted $substituted"
        awk '/RC=0  elapsed/ { printf "   %-8s %8s   %s\n", $1, $4, program($1) }
             function program(step) {
                 if (step == "STEP010") return "SORT     movements into account sequence"
                 if (step == "STEP020") return "ACCTPOST match-merge, streams"
                 if (step == "STEP030") return "SORT     new master into report sequence"
                 return "EODREPT  the printed report"
             }' "$run/cycle.txt"
        awk '/RC=0  elapsed/ { s = $4; sub("s$", "", s); total += s
                               if ($1 == "STEP010" || $1 == "STEP030") sorts += s }
             END { printf "   %-8s %7.3fs   of which the sort stand-in %.0f%%\n",
                          "WINDOW", total, 100 * sorts / total }' "$run/cycle.txt"
        echo "   peak RSS  cycle ${rss} GiB, writer ${writer_rss} GiB"
        echo
    } >> "$REPORT"

    cat "$run/rss.txt" >> "$run/cycle.txt"
    cp "$run/cycle.txt" "$OUT/cycle-$customers.txt"
    cp "$run/generate.txt" "$OUT/generate-$customers.txt"
done

{
    echo "== What the sorts cost, which is a property of this stand-in and not of the tier =="
    echo "  STEP010 and STEP030 are sortrec.py, which reads the whole file into a list. DFSORT"
    echo "  spills to work datasets and does not. STEP020 is ACCTPOST, which match-merges two"
    echo "  sorted files in one pass and never holds the master in memory - the property the tier"
    echo "  exists to demonstrate. Read the two apart."
    echo
    echo "  The writer figure is this fixture's own ceiling and not the cycle's: generate.py"
    echo "  --from-stream holds the whole day as Python objects before it writes a byte. It is"
    echo "  reported because it is what decides how large a day can be prepared on a given"
    echo "  machine, and it is the larger of the two."
    echo
} >> "$REPORT"

cat "$REPORT"
echo
echo "batch-window: written to $OUT"
