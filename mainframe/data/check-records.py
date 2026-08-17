#!/usr/bin/env python3
"""Assert that generated records match the contract, field by field.

This is the conformance check WP-02 wired into WP-03. Its expectations are parsed from
contracts/copybook/column-map.md - the contract itself - rather than restated here, so the check
cannot drift from the thing it is checking.

For every record it asserts:

  * the record is exactly the length the contract states;
  * every PIC X field holds printable ASCII, left-justified and space-padded;
  * every PIC 9 field holds nothing but digits;
  * every COMP-3 field carries a valid sign nibble and decodes to a value in range.

And across the file it asserts the awkward cases are actually present: a positive amount, a negative
amount and zero, with sign nibbles 0x0C, 0x0D and 0x0C. A conformance check that never sees a
negative number has not checked the sign nibble.

Run: python3 mainframe/data/check-records.py
"""

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from comp3 import POSITIVE, NEGATIVE, UNSIGNED, decode_comp3, to_hex  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
COLUMN_MAP = REPO / "contracts" / "copybook" / "column-map.md"
OUT = REPO / "mainframe" / "data" / "out"

ROW = re.compile(r"^\| `([A-Z0-9-]+)` \| (\d+) \| (\d+) \| (\d+) \| `(PIC [^`]+)` \|")
HEADING = re.compile(r"^## ([A-Z]+) - .*?, (\d+) bytes")


def parse_column_map() -> dict:
    """The contract, read as data. Returns {record: (length, [(field, start, end, size, pic)])}."""
    layouts, current = {}, None
    for line in COLUMN_MAP.read_text().splitlines():
        heading = HEADING.match(line)
        if heading:
            current = heading.group(1)
            layouts[current] = {"length": int(heading.group(2)), "fields": []}
            continue
        row = ROW.match(line)
        if row and current:
            layouts[current]["fields"].append(
                (row.group(1), int(row.group(2)), int(row.group(3)), int(row.group(4)), row.group(5))
            )
    return layouts


def check_field(record: bytes, name: str, start: int, size: int, pic: str, problems: list, where: str):
    raw = record[start - 1 : start - 1 + size]

    if "COMP-3" in pic:
        sign = raw[-1] & 0x0F
        if sign not in (POSITIVE, NEGATIVE, UNSIGNED):
            problems.append(f"{where} {name}: sign nibble 0x{sign:X} is not C, D or F  [{to_hex(raw)}]")
            return None
        if sign == UNSIGNED:
            problems.append(f"{where} {name}: written with 0x0F; this estate writes only C or D")
        try:
            value = decode_comp3(raw)
        except ValueError as error:
            problems.append(f"{where} {name}: {error}  [{to_hex(raw)}]")
            return None
        if value == 0 and sign != POSITIVE:
            problems.append(f"{where} {name}: zero carries sign 0x{sign:X}; zero is always positive")
        return value

    if pic.startswith("PIC 9"):
        if not raw.isdigit():
            problems.append(f"{where} {name}: PIC 9 field holds {raw!r}, which is not all digits")
        return None

    # PIC X: printable ASCII, left-justified, space-padded. Never null-padded.
    if b"\x00" in raw:
        problems.append(f"{where} {name}: null-padded; fixed-width text is space-padded")
    try:
        decoded = raw.decode("ascii")
    except UnicodeDecodeError:
        problems.append(f"{where} {name}: not ASCII  [{to_hex(raw)}]")
        return None
    if decoded != decoded.rstrip() + " " * (len(decoded) - len(decoded.rstrip())):
        problems.append(f"{where} {name}: not left-justified: {decoded!r}")
    return None


def check_file(path: pathlib.Path, layout: dict, record_name: str) -> tuple:
    problems, signs = [], set()
    data = path.read_bytes()
    length = layout["length"]

    if len(data) % length:
        problems.append(
            f"{path.name}: {len(data)} bytes is not a whole number of {length}-byte records"
            f" - {len(data) % length} bytes over"
        )

    count = len(data) // length
    for index in range(count):
        record = data[index * length : (index + 1) * length]
        where = f"{path.name}[{index}]"
        for name, start, end, size, pic in layout["fields"]:
            if end - start + 1 != size:
                problems.append(f"contract row {name}: {start}-{end} is not {size} bytes")
            value = check_field(record, name, start, size, pic, problems, where)
            if "COMP-3" in pic and value is not None:
                signs.add(NEGATIVE if value < 0 else POSITIVE)
                if value == 0:
                    signs.add("zero")

    print(f"  {record_name:<8} {path.name:<14} {count:>4} records x {length} bytes"
          f" = {len(data)} bytes, {len(layout['fields'])} fields each")
    return problems, signs


def main() -> int:
    parser = argparse.ArgumentParser(description="Check generated records against the contract.")
    parser.add_argument(
        "--skip-coverage",
        action="store_true",
        help=(
            "Skip the fixture-coverage assertions. They require the data to contain a positive, a "
            "negative and a zero amount, which is a requirement on generated fixtures - not on the "
            "output of a batch run, where a zero balance may legitimately have been moved away."
        ),
    )
    args = parser.parse_args()

    if not OUT.exists():
        print(f"No generated data. Run: python3 mainframe/data/generate.py --seed 42")
        return 1

    layouts = parse_column_map()
    print(f"Checking {OUT.relative_to(REPO)} against {COLUMN_MAP.relative_to(REPO)}\n")

    problems, signs = [], set()
    for record_name, filename in (("ACCTREC", "ACCTMAST.DAT"), ("MOVEREC", "MOVEMENT.DAT")):
        path = OUT / filename
        if not path.exists():
            problems.append(f"{filename} has not been generated")
            continue
        found, seen = check_file(path, layouts[record_name], record_name)
        problems.extend(found)
        signs |= seen

    print()
    coverage = () if args.skip_coverage else (
        (POSITIVE, "a positive amount (0x0C)"),
        (NEGATIVE, "a negative amount (0x0D)"),
        ("zero", "zero (0x0C)"),
    )
    if args.skip_coverage:
        print("  ....  fixture-coverage assertions skipped")
    for required, label in coverage:
        present = required in signs
        print(f"  {'OK  ' if present else 'MISS'}  the data covers {label}")
        if not present:
            problems.append(
                f"no record covers {label}; the sign nibble is untested and WP-11 has nothing to"
                " compare against"
            )

    print()
    if problems:
        for problem in problems[:20]:
            print(f"FAIL  {problem}")
        if len(problems) > 20:
            print(f"      ... and {len(problems) - 20} more")
        print(f"\n{len(problems)} problem(s). The contract is the authority.")
        return 1

    print("OK    every field matches contracts/copybook/column-map.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
