#!/usr/bin/env python3
"""Assert that the regulatory extract layout is a coherent fixed-width format.

The layout is declared in contracts/reporting/regulatory-extract-v1.md as a column map: one table
per record type, every field with its start, end, length and picture. This checker parses those
tables and proves the properties a fixed-width format has to have and a hand-maintained table
silently loses:

  * every record type starts at column 1 and runs to the declared record length, with no gap and no
    overlap between consecutive fields;
  * each field's stated length agrees with its start and end;
  * each field's stated length agrees with its picture clause;
  * every record type is the same number of bytes, because a reader seeks by record number;
  * the record-type discriminator occupies the same columns in all of them, because a reader must
    know which layout to apply before it can apply one.

It does not prove that the extract a run produces matches the map. That is the reporting component's
own test, and it is a different claim: this side says the map is a format, that side says the writer
implements it.

Standard library only, so it runs from a clean checkout with nothing installed.
"""

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
MAP = REPO / "contracts" / "reporting" / "regulatory-extract-v1.md"

# --------------------------------------------------------------------------------------------
# Transcribed from the format's own heading line, not read out of it. A record length that is only
# ever read from the document it is checking cannot catch the document changing.
# --------------------------------------------------------------------------------------------
RECORD_LENGTH = 200
RECORD_TYPES = ("HDR", "ACC", "TRL")
DISCRIMINATOR = ("REGEXT-REC-TYPE", 1, 3)

HEADING = re.compile(r"^##\s+(HDR|ACC|TRL)\b")
ROW = re.compile(
    r"^\|\s*`([A-Z0-9-]+)`\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*`([^`]+)`\s*\|"
)
PICTURE = re.compile(r"^PIC ([X9])\((\d+)\)$")


def picture_size(picture: str) -> int:
    """Bytes occupied by a picture clause.

    Display only. There is no COMP-3 here and that is deliberate: a packed field can contain 0x0D,
    which is why the stratum 0 files must be ORGANIZATION IS SEQUENTIAL. Every field of this format
    is printable ASCII, which is what lets the extract be a line-terminated text file that survives
    being opened, diffed and mailed.
    """
    match = PICTURE.match(picture)
    if not match:
        raise ValueError(f"unsupported picture clause {picture!r}")
    return int(match.group(2))


def parse(text: str) -> dict[str, list[tuple[str, int, int, int, str]]]:
    """Pull one field list per record type out of the column map."""
    records: dict[str, list[tuple[str, int, int, int, str]]] = {}
    current: str | None = None
    for line in text.splitlines():
        heading = HEADING.match(line)
        if heading:
            current = heading.group(1)
            records[current] = []
            continue
        row = ROW.match(line)
        if row and current:
            name, start, end, length, picture = row.groups()
            records[current].append((name, int(start), int(end), int(length), picture))
    return records


def check(records: dict[str, list[tuple[str, int, int, int, str]]]) -> list[str]:
    problems: list[str] = []

    missing = [name for name in RECORD_TYPES if name not in records]
    if missing:
        problems.append(f"no column map for record type(s): {', '.join(missing)}")

    for record_type in RECORD_TYPES:
        fields = records.get(record_type)
        if not fields:
            continue

        cursor = 1
        for name, start, end, length, picture in fields:
            where = f"{record_type}.{name}"
            if start != cursor:
                problems.append(
                    f"{where}: starts at column {start}, but the previous field ends at {cursor - 1}"
                )
            if end - start + 1 != length:
                problems.append(
                    f"{where}: columns {start}-{end} are {end - start + 1} bytes, length says {length}"
                )
            try:
                declared = picture_size(picture)
            except ValueError as error:
                problems.append(f"{where}: {error}")
            else:
                if declared != length:
                    problems.append(
                        f"{where}: {picture} occupies {declared} bytes, length says {length}"
                    )
            cursor = end + 1

        if cursor - 1 != RECORD_LENGTH:
            problems.append(
                f"{record_type}: fields cover {cursor - 1} bytes, the format is {RECORD_LENGTH}"
            )

        name, start, end = DISCRIMINATOR
        first = fields[0]
        if (first[0], first[1], first[2]) != (name, start, end):
            problems.append(
                f"{record_type}: record type discriminator must be {name} at columns {start}-{end}, "
                f"found {first[0]} at {first[1]}-{first[2]}"
            )

    return problems


def main() -> int:
    if not MAP.exists():
        print(f"FAIL  {MAP.relative_to(REPO)} does not exist")
        return 1

    records = parse(MAP.read_text(encoding="utf-8"))
    problems = check(records)

    if problems:
        print(f"FAIL  {MAP.relative_to(REPO)}")
        for problem in problems:
            print(f"        {problem}")
        return 1

    counted = sum(len(fields) for fields in records.values())
    print(
        f"OK    regulatory extract: {len(RECORD_TYPES)} record types, "
        f"{counted} fields, {RECORD_LENGTH} bytes each"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
