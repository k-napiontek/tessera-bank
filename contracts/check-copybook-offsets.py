#!/usr/bin/env python3
"""Assert that the copybooks still match the canonical data model.

The expectation below is transcribed from docs/architecture/canonical-data-model.md - the field
order, the picture clause of every field, and the stated record length. The actual layout is parsed
out of the .CPY files. Nothing is shared between the two sides, which is the only reason this is a
test rather than a restatement.

It fails when a field is added, removed, reordered, resized, or given a different picture, and when
a record no longer occupies its stated number of bytes.

    python3 contracts/check-copybook-offsets.py                 # check, printing the layouts
    python3 contracts/check-copybook-offsets.py --json MOVEREC   # one record's layout, as data

The --json view exists so that a reader in another language can assert its own field offsets against
this script's view of the copybook rather than counting characters by hand. It comes from the same
computation as the printed table - one function, two renderings - so the two cannot drift apart.
WP-11b's Java writer of MOVEREC is the first consumer.

Standard library only, so it runs from a clean checkout with nothing installed.
"""

import json
import math
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
COPYBOOKS = REPO / "contracts" / "copybook"
MODEL = "docs/architecture/canonical-data-model.md"

# --------------------------------------------------------------------------------------------
# Transcribed from the canonical data model. Change the model first, then this table.
# --------------------------------------------------------------------------------------------

EXPECTED = {
    "ACCTREC": {
        "length": 100,
        "fields": [
            ("ACCT-REF", "PIC X(16)"),
            ("ACCT-CUST-REF", "PIC X(12)"),
            ("ACCT-TYPE", "PIC X(09)"),
            ("ACCT-CURRENCY", "PIC X(03)"),
            ("ACCT-STATUS", "PIC X(07)"),
            ("ACCT-BOOKED-BAL", "PIC S9(13)V99 COMP-3"),
            ("ACCT-AVAIL-BAL", "PIC S9(13)V99 COMP-3"),
            ("ACCT-OPENED-DATE", "PIC 9(08)"),
            ("ACCT-LAST-MOVE-DATE", "PIC 9(08)"),
            ("FILLER", "PIC X(21)"),
        ],
    },
    "MOVEREC": {
        "length": 120,
        "fields": [
            ("MOV-TRANSFER-REF", "PIC X(20)"),
            ("MOV-LEG-NO", "PIC 9(02)"),
            ("MOV-ACCT-REF", "PIC X(16)"),
            ("MOV-DIRECTION", "PIC X(01)"),
            ("MOV-CURRENCY", "PIC X(03)"),
            ("MOV-AMOUNT", "PIC S9(13)V99 COMP-3"),
            ("MOV-VALUE-DATE", "PIC 9(08)"),
            ("MOV-POSTED-TS", "PIC 9(14)"),
            ("MOV-REFERENCE", "PIC X(35)"),
            ("FILLER", "PIC X(13)"),
        ],
    },
    "REJREC": {
        "length": 200,
        "fields": [
            ("REJ-MOVEMENT", "PIC X(120)"),
            ("REJ-REASON-CODE", "PIC X(04)"),
            ("REJ-REASON-TEXT", "PIC X(40)"),
            ("REJ-DETECTED-TS", "PIC 9(14)"),
            ("FILLER", "PIC X(22)"),
        ],
    },
}

# REJREC embeds MOVEREC verbatim, so the two lengths cannot drift apart independently.
EMBEDS = {("REJREC", "REJ-MOVEMENT"): "MOVEREC"}

COMP3 = re.compile(r"^PIC S9\((\d+)\)V9(?:\((\d+)\))?9? COMP-3$")
DISPLAY = re.compile(r"^PIC ([X9])\((\d+)\)$")
ENTRY = re.compile(r"^\s+05\s+(\S+)\s+(PIC .*)\.$")


def picture_size(pic: str) -> int:
    """Bytes occupied by a picture clause.

    COMP-3 packs two digits per byte with a trailing sign nibble, so a signed field of n digits
    takes ceil((n + 1) / 2) bytes. V is an implied decimal point and occupies nothing.
    """
    m = COMP3.match(pic)
    if m:
        digits = int(m.group(1)) + int(m.group(2) or 2)
        return math.ceil((digits + 1) / 2)
    m = DISPLAY.match(pic)
    if m:
        return int(m.group(2))
    raise ValueError(f"unrecognised picture clause: {pic!r}")


def parse(path: pathlib.Path):
    """Field name and picture clause for every 05-level entry, in file order."""
    fields = []
    for line in path.read_text().splitlines():
        if line[6:7] == "*" or not line.strip():
            continue
        m = ENTRY.match(line)
        if m:
            fields.append((m.group(1), m.group(2)))
    return fields


def layout(spec: dict) -> list:
    """Field name, 1-based inclusive start and end, size and picture, in file order.

    The one place offsets are computed. The printed table and the --json view both render this, so a
    consumer that reads either is reading the same arithmetic.
    """
    rows = []
    offset = 1
    for name, pic in spec["fields"]:
        size = picture_size(pic)
        rows.append((name, offset, offset + size - 1, size, pic))
        offset += size
    return rows


def as_json(record: str) -> str:
    """One record's layout as data, for a reader that is not Python."""
    rows = layout(EXPECTED[record])
    return json.dumps(
        {
            "record": record,
            "length": EXPECTED[record]["length"],
            "fields": [
                {"name": name, "start": start, "end": end, "size": size, "picture": pic}
                for name, start, end, size, pic in rows
            ],
        },
        indent=2,
    )


def check(record: str, spec: dict, show: bool = True) -> list:
    path = COPYBOOKS / f"{record}.CPY"
    problems = []

    if not path.exists():
        return [f"{record}: {path.relative_to(REPO)} does not exist"]

    actual = parse(path)
    expected = spec["fields"]

    if len(actual) != len(expected):
        problems.append(
            f"{record}: {len(actual)} fields in the copybook, {len(expected)} in the model"
        )

    rows = layout(spec)
    for i, (name, _start, _end, _size, pic) in enumerate(rows):
        got = actual[i] if i < len(actual) else ("<missing>", "<missing>")
        if got != (name, pic):
            problems.append(
                f"{record} field {i + 1}: model says {name} {pic}, copybook says {got[0]} {got[1]}"
            )

    total = rows[-1][2] if rows else 0
    if total != spec["length"]:
        problems.append(
            f"{record}: fields occupy {total} bytes, the model states {spec['length']}"
        )

    for (rec, field), embedded in EMBEDS.items():
        if rec != record:
            continue
        want = EXPECTED[embedded]["length"]
        size = next((r[3] for r in rows if r[0] == field), None)
        if size != want:
            problems.append(
                f"{record}.{field} is {size} bytes but embeds {embedded}, which is {want}"
            )

    if show:
        width = max(len(r[0]) for r in rows)
        print(f"\n{record}  -  {total} bytes")
        for name, start, end, size, pic in rows:
            print(f"  {name:<{width}}  {start:>4}-{end:<4} {size:>4}  {pic}")

    return problems


def self_test() -> list:
    """The size rule itself, checked against the worked examples in the model."""
    cases = [("PIC S9(13)V99 COMP-3", 8), ("PIC X(16)", 16), ("PIC 9(14)", 14)]
    problems = [
        f"picture_size({pic!r}) returned {picture_size(pic)}, expected {want}"
        for pic, want in cases
        if picture_size(pic) != want
    ]

    # And the arithmetic that turns sizes into offsets, against the one placement the model works
    # out longhand: MOV-AMOUNT is the packed field, and it starts where thirteen display bytes and
    # a two-byte leg number and a sixteen-byte account reference have already been spent.
    amount = next(row for row in layout(EXPECTED["MOVEREC"]) if row[0] == "MOV-AMOUNT")
    if (amount[1], amount[2], amount[3]) != (43, 50, 8):
        problems.append(
            f"MOVEREC.MOV-AMOUNT computed at {amount[1]}-{amount[2]} ({amount[3]} bytes),"
            " the model says 43-50 (8 bytes)"
        )

    return problems


def main() -> int:
    if len(sys.argv) > 1:
        if sys.argv[1] != "--json" or len(sys.argv) != 3:
            print(f"usage: {pathlib.Path(sys.argv[0]).name} [--json RECORD]", file=sys.stderr)
            print(f"       RECORD is one of {', '.join(EXPECTED)}", file=sys.stderr)
            return 2
        record = sys.argv[2]
        if record not in EXPECTED:
            print(f"no such record {record}; known: {', '.join(EXPECTED)}", file=sys.stderr)
            return 2
        # The check still runs, so --json can never report a layout the copybook has drifted from.
        problems = self_test() + check(record, EXPECTED[record], show=False)
        if problems:
            for problem in problems:
                print(f"FAIL  {problem}", file=sys.stderr)
            return 1
        print(as_json(record))
        return 0

    print(f"Checking {COPYBOOKS.relative_to(REPO)} against {MODEL}")

    problems = self_test()
    for record, spec in EXPECTED.items():
        problems.extend(check(record, spec))

    print()
    if problems:
        for p in problems:
            print(f"FAIL  {p}")
        print(f"\n{len(problems)} mismatch(es). The model is the authority: correct the copybook,")
        print("or change the model first and then everything derived from it.")
        return 1

    print(f"OK    {len(EXPECTED)} records match the canonical model")
    return 0


if __name__ == "__main__":
    sys.exit(main())
