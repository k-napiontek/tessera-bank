#!/usr/bin/env python3
"""Sort a fixed-length record file on byte-range keys. The local stand-in for DFSORT.

Unix `sort` is line-based, and these files are not lines. A COMP-3 amount can pack to a trailing
0x0D - a negative amount ending in a zero digit does exactly that - and a fixed-width record is
padded with the 0x20 bytes that `sort` and every line-oriented tool feel free to strip. Sorting
these files with `sort` splits records in the wrong places and pads them back to the wrong length,
and the result still looks like a file. This tool never interprets the bytes it moves; it slices
fixed-length records and orders them by the byte ranges it is given.

**This is not an external sort. DFSORT is.** The real utility spills to work datasets and sorts
files far larger than the memory of the machine running it; this reads the whole file into a list.
Nothing here should be read as evidence that the local cycle handles a master larger than memory.
Only the COBOL does that, and only because it never sorts - it match-merges two already-sorted
files in one pass.

    sortrec.py --record-length 120 --key 22:38 IN OUT              # movements by MOV-ACCT-REF
    sortrec.py --record-length 100 --key 37:40 --key 0:16 IN OUT   # master by currency, then ref

Keys are zero-based, end-exclusive byte offsets into the record, applied in the order given. The
comparison is by raw bytes, which for the EBCDIC-free ASCII fixed-width fields in this repository
is the same order COBOL's own comparison produces.
"""

import argparse
import pathlib
import sys


class SortError(Exception):
    pass


def parse_key(value: str) -> tuple:
    """'22:38' -> (22, 38). Rejects anything that would silently sort on the wrong field."""
    try:
        start, end = value.split(":")
        start, end = int(start), int(end)
    except ValueError:
        raise SortError(f"key {value!r} is not START:END") from None
    if start < 0 or end <= start:
        raise SortError(f"key {value!r} is empty or reversed")
    return start, end


def split_records(data: bytes, length: int) -> list:
    """A ragged file is an error, never a truncated read.

    A file whose length is not a multiple of the record length means the step before this one wrote
    a partial record, and dropping the tail would hide that from every step after it.
    """
    if len(data) % length:
        raise SortError(
            f"{len(data)} bytes is not a whole number of {length}-byte records "
            f"({len(data) % length} left over)"
        )
    return [data[i : i + length] for i in range(0, len(data), length)]


def sort_records(records: list, keys: list, length: int) -> list:
    for start, end in keys:
        if end > length:
            raise SortError(f"key {start}:{end} runs past the {length}-byte record")
    # Python's sort is stable, so records with equal keys keep the order they arrived in - which is
    # what DFSORT does with EQUALS in effect, and what makes the cycle reproducible.
    return sorted(records, key=lambda record: tuple(record[s:e] for s, e in keys))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Sort a fixed-length record file.")
    parser.add_argument("--record-length", type=int, required=True)
    parser.add_argument("--key", action="append", required=True, metavar="START:END",
                        help="zero-based, end-exclusive byte range; repeatable, in order")
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("target", type=pathlib.Path)
    args = parser.parse_args(argv)

    try:
        if args.record_length <= 0:
            raise SortError(f"record length {args.record_length} is not positive")
        keys = [parse_key(key) for key in args.key]
        records = split_records(args.source.read_bytes(), args.record_length)
        args.target.write_bytes(b"".join(sort_records(records, keys, args.record_length)))
    except SortError as error:
        print(f"SORTREC ERROR {error}", file=sys.stderr)
        return 12
    except OSError as error:
        print(f"SORTREC ERROR {error}", file=sys.stderr)
        return 12

    print(f"SORTREC {len(records):>9} records  {args.source.name} -> {args.target.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
