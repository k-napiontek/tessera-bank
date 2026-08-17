#!/usr/bin/env python3
"""Assert that the mainframe copybooks and the contract copybooks are the same files.

REQ-MF-001 requires a record layout to be defined exactly once. Two directories each holding a copy
satisfies that only if something enforces it, so this does:

  * the same set of .CPY files exists in both places - a file in one and not the other is a failure;
  * every file is byte-identical, not merely equivalent.

Byte-identical rather than semantically equal is deliberate. In a fixed-format language a difference
in trailing spaces is a difference in what the compiler sees, and "equivalent" is exactly the sort of
judgement that lets a layout drift.

Standard library only, so it runs from a clean checkout.
"""

import filecmp
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
CONTRACT = REPO / "contracts" / "copybook"
MAINFRAME = REPO / "mainframe" / "copybook"


def copybooks(directory: pathlib.Path) -> dict:
    return {path.name: path for path in sorted(directory.glob("*.CPY"))}


def main() -> int:
    contract = copybooks(CONTRACT)
    mainframe = copybooks(MAINFRAME)

    problems = []

    if not contract:
        problems.append(f"{CONTRACT.relative_to(REPO)} holds no copybooks at all")

    for name in sorted(set(contract) - set(mainframe)):
        problems.append(f"{name} is in contracts/copybook but not mainframe/copybook")
    for name in sorted(set(mainframe) - set(contract)):
        problems.append(
            f"{name} is in mainframe/copybook but not contracts/copybook"
            " - the contract is the source, so nothing may originate here"
        )

    for name in sorted(set(contract) & set(mainframe)):
        same = filecmp.cmp(contract[name], mainframe[name], shallow=False)
        size = contract[name].stat().st_size
        print(f"  {'OK  ' if same else 'DIFF'}  {name:<14} {size:>5} bytes")
        if not same:
            problems.append(
                f"{name} differs between contracts/copybook and mainframe/copybook."
                " The contract is the authority: change it first, then copy."
            )

    print()
    if problems:
        for problem in problems:
            print(f"FAIL  {problem}")
        return 1

    print(f"OK    {len(contract)} copybooks identical to the contract")
    return 0


if __name__ == "__main__":
    sys.exit(main())
