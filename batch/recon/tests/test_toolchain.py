"""The interpreter this tier is pinned to, asserted rather than assumed.

``requires-python`` constrains what ``uv`` will *resolve*; it does not constrain what a developer
running ``pytest`` by hand is actually inside. This test is the one that fails on the machine where
those two have come apart, which is the only situation in which the pin is doing any work at all.
"""

from __future__ import annotations

import sys


def test_the_tier_runs_on_python_312() -> None:
    assert sys.version_info[:2] == (3, 12), (
        f"batch/recon is pinned to Python 3.12 by CLAUDE.md and pyproject.toml; "
        f"this interpreter is {sys.version_info.major}.{sys.version_info.minor}"
    )


def test_the_package_imports() -> None:
    import recon

    assert recon.__name__ == "recon"
