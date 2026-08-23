#!/usr/bin/env python3
"""Tests for the documentation checker.

Each test builds a small repository in a temporary directory and runs the checker against it as a
subprocess, so what is asserted is the real entry point - the exit status a build reacts to and the
line an engineer reads - rather than a function called past it.

The fixtures are deliberately tiny and deliberately not this repository. A checker tested only
against the tree it ships in passes for as long as that tree happens to be clean, and says nothing
about what it does when a link breaks.

Run: python3 quality/test-docs-check.py
"""

import contextlib
import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

CHECKER = pathlib.Path(__file__).resolve().parent / "docs-check.py"

#: The fixture prefix is **assembled rather than written**, and that is not fussiness: the checker
#: under test forbids any requirement id that resolves to nothing, anywhere in the repository, and
#: these fixtures deliberately resolve to nothing. Written as a literal, this file would fail the
#: check it exists to verify. The rule binding its own tests is the rule working.
FIX = "REQ-" + "FIX"

#: The checker refuses a catalogue it cannot parse, so every fixture needs a plausible one.
CATALOGUE_IDS = [f"{FIX}-{n:03d}" for n in range(1, 46)]


def matrix(ids=None) -> str:
    rows = "\n".join(f"| {i} | A fixture requirement | WP-00 |" for i in ids or CATALOGUE_IDS)
    return textwrap.dedent(
        """\
        # Requirements traceability matrix

        ## Requirement catalogue

        | ID | Requirement | Owned by |
        |---|---|---|
        """
    ) + rows + "\n\n## WP-00 - a package\n\nNothing to see.\n"


class Fixture:
    """A throwaway repository with a parseable requirement catalogue in it."""

    def __init__(self, stack, ids=None):
        self.root = pathlib.Path(stack.enter_context(tempfile.TemporaryDirectory()))
        self.write("docs/compliance/traceability-matrix.md", matrix(ids))

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text), encoding="utf-8")
        return path

    def run(self):
        return subprocess.run(
            [sys.executable, str(CHECKER), str(self.root)],
            capture_output=True,
            text=True,
        )


class DocsCheck(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.fixture = Fixture(self.stack)

    def test_a_clean_tree_passes(self):
        self.fixture.write("docs/a.md", "# A\n\nSee [B](b.md) and [the web](https://example.org).\n")
        self.fixture.write("docs/b.md", "# B\n")
        result = self.fixture.run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK    no stub marker remains", result.stdout)

    def test_a_link_to_a_missing_file_fails(self):
        self.fixture.write("docs/a.md", "# A\n\nSee [B](b.md).\n")
        result = self.fixture.run()
        self.assertEqual(result.returncode, 1)
        self.assertIn("docs/a.md:3  b.md - no such file", result.stdout)

    def test_a_link_to_a_missing_section_fails(self):
        self.fixture.write("docs/a.md", "# A\n\nSee [B](b.md#the-middle).\n")
        self.fixture.write("docs/b.md", "# B\n\n## The end\n")
        result = self.fixture.run()
        self.assertEqual(result.returncode, 1)
        self.assertIn("b.md#the-middle - no such section", result.stdout)

    def test_a_section_that_exists_passes(self):
        self.fixture.write("docs/a.md", "# A\n\nSee [B](b.md#the-end) and [up](#a).\n")
        self.fixture.write("docs/b.md", "# B\n\n## The end\n")
        self.assertEqual(self.fixture.run().returncode, 0)

    def test_a_repeated_heading_offers_the_suffixed_anchor(self):
        # GitHub mints heading, heading-1, heading-2 for duplicates. A link to the second one is
        # correct and must not be reported.
        self.fixture.write("docs/a.md", "# A\n\nSee [again](b.md#same).\nAnd [twice](b.md#same-1).\n")
        self.fixture.write("docs/b.md", "# B\n\n## Same\n\n## Same\n")
        self.assertEqual(self.fixture.run().returncode, 0)

    def test_a_stub_marker_fails(self):
        self.fixture.write("docs/a.md", "# A\n\n> **STUB.** Outline only. Filled by **WP-00**.\n")
        result = self.fixture.run()
        self.assertEqual(result.returncode, 1)
        self.assertIn("docs/a.md:3  stub marker", result.stdout)

    def test_prose_about_stubs_does_not_trip_the_marker(self):
        self.fixture.write("docs/a.md", "# A\n\nNo stub document remains, and STUB is the marker.\n")
        self.assertEqual(self.fixture.run().returncode, 0)

    def test_an_id_outside_the_catalogue_fails_wherever_it_is(self):
        # Not a markdown file: the trap this rule exists for is an id used in a test name or a
        # migration comment, where nobody would think to look for it.
        self.fixture.write("services/V9__audit.sql", f"-- satisfies {FIX}-999\n")
        result = self.fixture.run()
        self.assertEqual(result.returncode, 1)
        self.assertIn(f"{FIX}-999 is in no catalogue", result.stdout)

    def test_an_id_in_the_catalogue_passes(self):
        self.fixture.write("docs/a.md", f"# A\n\nSatisfies {FIX}-001.\n")
        self.assertEqual(self.fixture.run().returncode, 0)

    def test_a_link_inside_a_fenced_block_is_an_example_rather_than_a_link(self):
        self.fixture.write(
            "docs/a.md",
            """\
            # A

            ```markdown
            [a broken example](nowhere.md)
            ```
            """,
        )
        self.assertEqual(self.fixture.run().returncode, 0)

    def test_a_link_in_a_code_span_is_not_a_link(self):
        self.fixture.write("docs/a.md", "# A\n\nWrite `[text](target.md)` to link.\n")
        self.assertEqual(self.fixture.run().returncode, 0)

    def test_a_catalogue_it_cannot_parse_is_refused_rather_than_believed(self):
        # The dangerous failure is the quiet one: a catalogue that parses to nothing would accept
        # every id in the repository and report OK.
        fixture = Fixture(self.stack, ids=CATALOGUE_IDS[:3])
        fixture.write("docs/a.md", f"# A\n\nSatisfies {FIX}-999.\n")
        result = fixture.run()
        self.assertEqual(result.returncode, 1)
        self.assertIn("only 3 ids parsed", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
