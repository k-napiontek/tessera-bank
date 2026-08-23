#!/usr/bin/env python3
"""Hold the documentation of this repository to three things a reader has to be able to trust.

Every internal link resolves, no stub survives, and no requirement id is invented.

**This exists because four work packages closed over their own stubs.** `governance/tech-radar.md`
and `ways-of-working/dependency-policy.md` belong to WP-02, `ways-of-working/test-strategy.md` to
WP-06 and `compliance/psd2-notes.md` to WP-12; all four packages are merged and every one of those
documents still carried `> **STUB.**` when WP-18b started. The Definition of Done requires a
*directory* README to be updated when that directory changes, so a document that belongs to no
directory anybody touched is a document nothing checks - which is **F-17**, open since WP-01 and
recorded again every time it happens. WP-18's own Definition of Done says *no stub documents remain*
and *every internal markdown link resolves*, and a box like that is worth exactly as much as the
thing that fails when it stops being true.

Three assertions, over the whole tree:

1. **Every internal link resolves**, including its `#anchor`. A link into a document that was moved
   or a section that was renamed is worse than no link, because it reads as a citation.
2. **No `> **STUB.**` marker remains.** The marker is the repository's own convention for "outline
   only"; it is checked rather than the word "stub", so prose *about* stubs does not trip it.
3. **Every `REQ-*` id used anywhere in the repository is defined in the catalogue** in
   `docs/compliance/traceability-matrix.md`. `CLAUDE.md` names inventing an id as one of the traps
   already caught here - it cost fourteen collisions in WP-02 - and until now nothing enforced it.
   It found one on its first run, in the traceability matrix itself, under a prefix this repository
   has never had.

**That third rule binds this file too, and every document that discusses it.** An id that resolves to
nothing may not be written down here at all - not as an example, not in a docstring, and not in the
note recording where one was found - because a reader sampling the matrix cannot tell a cautionary
example from a real requirement. So the invented id above is named by its family and not quoted,
which is also the more accurate description: the prefix, not just the number, was invented.

The third assertion runs one way only. An id that is *used* must be *defined*; an id that is defined
and resolved by no per-package section is not caught here, because "resolved" is a judgement about
prose rather than a string that either appears or does not. That direction is a follow-up, not a
silent gap.

Standard library only, so it runs from a clean checkout with nothing installed. It takes an optional
root, so it can be pointed at a worktree of another commit - which is how WP-18b evidenced that it
fails before the documentation pass and passes after.

Run: python3 quality/docs-check.py [ROOT]
"""

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

#: Never walked. Build output, tool caches and vendored code are not this repository's
#: documentation, and node_modules alone would multiply the file count by three orders of magnitude.
#: The cache directories earn their place here: pytest drops a README of its own into every one, and
#: a checker whose file count depends on whether the tests have been run lately is a checker that
#: reports something different on a clean checkout.
SKIP_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".tox",
    ".venv",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
    "target",
    "venv",
}

#: Read for requirement ids but never for links. Anything larger is a capture or a fixture.
MAX_SCAN_BYTES = 2 * 1024 * 1024

#: Binary by extension. Reading these with errors="replace" would work and would only ever waste time.
BINARY_SUFFIXES = {
    ".class", ".dat", ".gif", ".gz", ".ico", ".jar", ".jpeg", ".jpg", ".pdf",
    ".png", ".so", ".svg", ".tar", ".ttf", ".war", ".webp", ".woff", ".woff2", ".zip",
}

LINK = re.compile(r"(?<!\\)!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HEADING = re.compile(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$")
STUB = re.compile(r"^\s*>\s*\*\*STUB\.\*\*")
REQUIREMENT = re.compile(r"REQ-[A-Z]+-[0-9]{3}")
EXTERNAL = ("http://", "https://", "mailto:", "tel:")


def walk(root: pathlib.Path):
    """Every file under root, minus the directories nothing here owns."""
    return (
        path
        for path in root.rglob("*")
        if path.is_file() and not (SKIP_DIRS & set(path.relative_to(root).parts))
    )


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def without_code(text: str) -> list:
    """The lines of text with code blanked out, so a fenced example cannot fail a real check.

    Line numbers are preserved - a blanked line stays a line - because every problem this checker
    reports names one, and an off-by-a-fence line number sends the reader to the wrong place.
    """
    lines = []
    fence = None
    for line in text.splitlines():
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if fence is None and marker:
            fence = marker.group(1)[0] * 3
            lines.append("")
            continue
        if fence is not None:
            lines.append("")
            if marker and marker.group(1).startswith(fence):
                fence = None
            continue
        lines.append(re.sub(r"`[^`]*`", "", line))
    return lines


def slug(heading: str) -> str:
    """GitHub's anchor for a heading: link text kept, formatting and punctuation dropped."""
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    text = re.sub(r"[`*_~]", "", text).strip().lower()
    return re.sub(r"[^a-z0-9 \-]", "", text).replace(" ", "-")


def anchors(path: pathlib.Path) -> set:
    """Every anchor a markdown file offers, including the duplicate-suffixed ones GitHub mints."""
    found, seen = set(), {}
    for line in without_code(read(path)):
        heading = HEADING.match(line)
        if not heading:
            continue
        base = slug(heading.group(1))
        seen[base] = seen.get(base, -1) + 1
        found.add(base if seen[base] == 0 else f"{base}-{seen[base]}")
    return found


def check_links(files: list, root: pathlib.Path) -> tuple:
    """Every internal link, path and fragment both."""
    known, problems, checked = {}, [], 0
    for path in files:
        for number, line in enumerate(without_code(read(path)), start=1):
            for href in LINK.findall(line):
                if href.startswith(EXTERNAL):
                    continue
                checked += 1
                where = f"{path.relative_to(root)}:{number}"
                target, _, fragment = href.partition("#")
                destination = path if not target else (path.parent / target).resolve()
                if target and not destination.exists():
                    problems.append(f"{where}  {href} - no such file")
                    continue
                if not fragment or destination.suffix != ".md" or not destination.is_file():
                    continue
                if destination not in known:
                    known[destination] = anchors(destination)
                if fragment not in known[destination]:
                    problems.append(f"{where}  {href} - no such section")
    return problems, checked


def check_stubs(files: list, root: pathlib.Path) -> list:
    """The repository's own outline-only marker, which WP-18's Definition of Done forbids."""
    problems = []
    for path in files:
        for number, line in enumerate(read(path).splitlines(), start=1):
            if STUB.match(line):
                problems.append(f"{path.relative_to(root)}:{number}  stub marker")
    return problems


def catalogue(root: pathlib.Path) -> set:
    """The ids defined in the requirement catalogue, which is the authority for all of them."""
    matrix = root / "docs" / "compliance" / "traceability-matrix.md"
    if not matrix.is_file():
        raise SystemExit(f"FAIL  {matrix} does not exist; there is no authority to check against")
    section, defined = False, set()
    for line in read(matrix).splitlines():
        if line.startswith("## "):
            section = line.strip() == "## Requirement catalogue"
            continue
        if section and line.startswith("|"):
            defined.update(REQUIREMENT.findall(line.split("|")[1]))
    # A parse that quietly finds nothing would agree with every id in the repository, which is the
    # failure mode this checker exists to end rather than to reproduce.
    if len(defined) < 40:
        raise SystemExit(
            f"FAIL  only {len(defined)} ids parsed out of the requirement catalogue."
            " The catalogue moved or its shape changed; fix this checker before trusting it."
        )
    return defined


def check_requirement_ids(root: pathlib.Path) -> tuple:
    """Every id used anywhere against every id defined, in that direction."""
    defined = catalogue(root)
    problems, used = [], set()
    for path in walk(root):
        if path.suffix.lower() in BINARY_SUFFIXES or path.stat().st_size > MAX_SCAN_BYTES:
            continue
        for number, line in enumerate(read(path).splitlines(), start=1):
            for identifier in REQUIREMENT.findall(line):
                used.add(identifier)
                if identifier not in defined:
                    problems.append(
                        f"{path.relative_to(root)}:{number}  {identifier} is in no catalogue"
                    )
    return problems, defined, used


def main() -> int:
    root = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else REPO
    if not root.is_dir():
        print(f"usage: {pathlib.Path(sys.argv[0]).name} [ROOT]", file=sys.stderr)
        return 2

    files = sorted(path for path in walk(root) if path.suffix == ".md")
    print(f"Checking {len(files)} markdown files under {root}")

    link_problems, links = check_links(files, root)
    stub_problems = check_stubs(files, root)
    id_problems, defined, used = check_requirement_ids(root)

    print()
    for problem in link_problems + stub_problems + id_problems:
        print(f"FAIL  {problem}")

    if link_problems:
        print(f"\n{len(link_problems)} internal link(s) resolve to nothing.")
    else:
        print(f"OK    {links} internal links resolve, sections included")

    if stub_problems:
        print(f"{len(stub_problems)} stub marker(s) remain. A stub is a document nobody wrote.")
    else:
        print("OK    no stub marker remains")

    if id_problems:
        print(f"{len(id_problems)} requirement id(s) are in no catalogue. Never invent one:")
        print("      add it to the owning work package, then to the catalogue, then use it.")
    else:
        print(f"OK    {len(used)} requirement ids used, all {len(defined)} in the catalogue")

    return 1 if link_problems or stub_problems or id_problems else 0


if __name__ == "__main__":
    sys.exit(main())
