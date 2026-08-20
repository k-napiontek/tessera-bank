#!/usr/bin/env python3
"""Assert that the reconciliation break report format is coherent.

The format is declared in contracts/recon/break-report-v1.md as a set of field tables - the top
level, three nested objects, a break, and the control totals - plus a table of classifications.
This checker parses those tables and proves the properties a hand-maintained document silently
loses:

  * no table declares a field twice, and no two tables disagree about a field they share;
  * every field's type is one the format's own Types table defines;
  * every classification the break table's prose depends on appears in the classification table,
    and every classification in that table is reachable - a value nothing can produce and a value
    nothing documents are the same defect seen from two sides;
  * the objects the top level refers to are all defined, and no object is defined and unreferenced.

It does not prove that a report a run produces matches the tables. That is batch/recon's own test,
and it is a different claim: this side says the document is a format, that side says the writer
implements it. The same division as check-extract-layout.py next door.

Standard library only, so it runs from a clean checkout with nothing installed.
"""

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SPEC = REPO / "contracts" / "recon" / "break-report-v1.md"

# --------------------------------------------------------------------------------------------
# Transcribed from the format, not read out of it. A checker whose expectations come entirely from
# the document it is checking agrees with every version of that document, including a wrong one.
# --------------------------------------------------------------------------------------------
FORMAT_ID = "TB-RECON-BREAKS-V1"
OBJECTS = ("cutOff", "masterFile", "totals")
CLASSIFICATIONS = ("VALUE_DRIFT", "MISSING_ON_MASTER", "MISSING_IN_LEDGER", "TIMING")

SECTION = re.compile(r"^##\s+(.+?)\s*$")
FIELD_ROW = re.compile(r"^\|\s*`([A-Za-z0-9_]+)`\s*\|\s*`([A-Za-z0-9?]+)`\s*\|")
CLASS_ROW = re.compile(r"^\|\s*`([A-Z_]+)`\s*\|")
TYPE_ROW = re.compile(r"^\|\s*`([A-Za-z0-9?]+)`\s*\|\s*(.+?)\s*\|\s*$")

#: Section heading -> the name this checker knows it by. Headings that carry no field table - the
#: prose sections - are absent on purpose.
TABLES = {
    "Top level": "top",
    "`cutOff`": "cutOff",
    "`masterFile`": "masterFile",
    "A break": "break",
    "`totals`": "totals",
}


def parse(text: str) -> tuple[dict[str, list[tuple[str, str]]], list[str], set[str]]:
    """Field tables by section, the classifications, and the declared type names."""
    tables: dict[str, list[tuple[str, str]]] = {}
    classifications: list[str] = []
    types: set[str] = set()
    section = None
    for line in text.splitlines():
        heading = SECTION.match(line)
        if heading:
            section = heading.group(1)
            continue
        if section in TABLES:
            row = FIELD_ROW.match(line)
            if row:
                tables.setdefault(TABLES[section], []).append((row.group(1), row.group(2)))
        elif section == "Classifications":
            row = CLASS_ROW.match(line)
            if row:
                classifications.append(row.group(1))
        elif section == "Types":
            row = TYPE_ROW.match(line)
            # The header separator row matches the shape of a row; its first cell is not a type.
            if row and not set(row.group(1)) <= {"-"}:
                types.add(row.group(1))
    return tables, classifications, types


def check(
    tables: dict[str, list[tuple[str, str]]], classifications: list[str], types: set[str]
) -> list[str]:
    problems: list[str] = []

    missing = sorted(set(TABLES.values()) - set(tables))
    if missing:
        problems.append(f"no field table for: {', '.join(missing)}")
        return problems

    for name, fields in sorted(tables.items()):
        seen: set[str] = set()
        for field, kind in fields:
            if field in seen:
                problems.append(f"{name}: field {field} is declared twice")
            seen.add(field)
            if kind not in types:
                problems.append(f"{name}.{field}: type `{kind}` is not in the Types table")

    top = dict(tables["top"])
    if "formatId" not in top:
        problems.append("top level: no formatId")
    if FORMAT_ID not in SPEC.read_text(encoding="utf-8"):
        problems.append(f"the format id {FORMAT_ID} appears nowhere in the document")

    referenced = {field for field, kind in tables["top"] if kind == "object"}
    if referenced != set(OBJECTS):
        problems.append(
            f"the top level refers to objects {sorted(referenced)}, "
            f"and the format defines {sorted(OBJECTS)}"
        )

    if sorted(classifications) != sorted(CLASSIFICATIONS):
        problems.append(
            f"classifications are {sorted(classifications)}, expected {sorted(CLASSIFICATIONS)}"
        )

    breaks = dict(tables["break"])
    for side in ("masterBookedMinor", "ledgerBookedMinor", "differenceMinor"):
        if breaks.get(side) != "integer?":
            problems.append(
                f"break.{side} is `{breaks.get(side)}`; it must be `integer?`, because a break "
                f"with one side absent has no figure for the other"
            )

    totals = dict(tables["totals"])
    for total in ("accountsCompared", "accountsMatched", "accountsBroken"):
        if totals.get(total) != "integer":
            problems.append(f"totals.{total} is `{totals.get(total)}`; it must be `integer`")

    return problems


def main() -> int:
    if not SPEC.exists():
        print(f"FAIL  {SPEC.relative_to(REPO)} does not exist")
        return 1

    tables, classifications, types = parse(SPEC.read_text(encoding="utf-8"))
    problems = check(tables, classifications, types)
    if problems:
        print(f"FAIL  {SPEC.relative_to(REPO)}")
        for problem in problems:
            print(f"        {problem}")
        return 1

    fields = sum(len(rows) for rows in tables.values())
    print(
        f"OK    {FORMAT_ID}: {len(tables)} tables, {fields} fields, "
        f"{len(classifications)} classifications"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
