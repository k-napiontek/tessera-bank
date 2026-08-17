#!/usr/bin/env python3
"""Assert that the copybooks still match the canonical data model.

The expectation below is transcribed from docs/architecture/canonical-data-model.md - the field
order, the picture clause of every field, and the stated record length. The actual layout is parsed
out of the .CPY files. Nothing is shared between the two sides, which is the only reason this is a
test rather than a restatement.

It fails when a field is added, removed, reordered, resized, or given a different picture, and when
a record no longer occupies its stated number of bytes.

Standard library only, so it runs from a clean checkout with nothing installed.
"""

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


def check(record: str, spec: dict) -> list:
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

    offset = 1
    rows = []
    for i, (name, pic) in enumerate(expected):
        got = actual[i] if i < len(actual) else ("<missing>", "<missing>")
        size = picture_size(pic)
        rows.append((name, offset, offset + size - 1, size, pic))
        if got != (name, pic):
            problems.append(
                f"{record} field {i + 1}: model says {name} {pic}, copybook says {got[0]} {got[1]}"
            )
        offset += size

    total = offset - 1
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

    width = max(len(r[0]) for r in rows)
    print(f"\n{record}  -  {total} bytes")
    for name, start, end, size, pic in rows:
        print(f"  {name:<{width}}  {start:>4}-{end:<4} {size:>4}  {pic}")

    return problems


def self_test() -> list:
    """The size rule itself, checked against the worked examples in the model."""
    cases = [("PIC S9(13)V99 COMP-3", 8), ("PIC X(16)", 16), ("PIC 9(14)", 14)]
    return [
        f"picture_size({pic!r}) returned {picture_size(pic)}, expected {want}"
        for pic, want in cases
        if picture_size(pic) != want
    ]


def main() -> int:
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
