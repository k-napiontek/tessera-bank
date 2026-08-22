#!/usr/bin/env python3
"""Run a command and report the peak resident set size of it and its children.

The cycle's memory is the measurement WP-25a wants alongside its wall clock, because STEP010 and
STEP030 call sortrec.py, which reads the whole file into a list. DFSORT spills to work datasets and
does not, so the number below is a property of the local stand-in rather than of the tier - and it
is the ceiling that decides how large a day this cycle can be run over at all.

Written in Python rather than as `/usr/bin/time -l` because that flag is BSD's, GNU's is -v, and
ru_maxrss is reported in bytes on macOS and in kilobytes on Linux. One place to get that wrong is
better than three.

    python3 workload/scripts/run-with-rss.py -- bash mainframe/jcl/run-eod.sh --business-date 20260302

Prints the command's own output, then one line to stderr:

    RSS-PEAK-BYTES 5530861568
"""

import resource
import subprocess
import sys


def peak_bytes(ru_maxrss: int) -> int:
    """ru_maxrss is bytes on Darwin and kilobytes everywhere else Python runs."""
    return ru_maxrss if sys.platform == "darwin" else ru_maxrss * 1024


def main() -> int:
    argv = sys.argv[1:]
    if argv and argv[0] == "--":
        argv = argv[1:]
    if not argv:
        print("run-with-rss: nothing to run", file=sys.stderr)
        return 2

    before = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
    completed = subprocess.run(argv)
    after = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss

    # RUSAGE_CHILDREN is a high-water mark over every child this process has ever reaped, so it
    # never falls. Reporting the reading rather than the difference is the honest form: if an
    # earlier child peaked higher, this number is that peak and says so by being unchanged.
    print(f"RSS-PEAK-BYTES {peak_bytes(max(before, after))}", file=sys.stderr)
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
