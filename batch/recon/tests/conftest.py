"""Fixtures shared by the suite: the synthetic account master, and the repository root.

The master is produced by ``mainframe/data/generate.py`` at a fixed seed rather than committed as a
binary fixture. Same seed, same bytes - the generator's own promise - and reading what stratum 0
actually writes is the whole point: a reconciliation verified against a file this tier wrote itself
would be checking its own understanding of the format.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
from collections.abc import Iterator

import pytest

REPO = pathlib.Path(__file__).resolve().parents[3]
GENERATOR = REPO / "mainframe" / "data" / "generate.py"
OUT = REPO / "mainframe" / "data" / "out"

SEED = 42


@pytest.fixture(scope="session")
def repo() -> pathlib.Path:
    return REPO


@pytest.fixture(scope="session")
def master_file() -> Iterator[pathlib.Path]:
    """`ACCTMAST.DAT` as the WP-03 generator writes it, at the seed the estate uses everywhere."""
    subprocess.run(  # noqa: S603 - fixed argv, no shell, path from __file__
        [sys.executable, str(GENERATOR), "--seed", str(SEED)],
        cwd=REPO,
        check=True,
        capture_output=True,
    )
    path = OUT / "ACCTMAST.DAT"
    if not path.is_file():
        raise RuntimeError(f"the generator did not produce {path}")
    yield path
