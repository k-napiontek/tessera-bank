#!/usr/bin/env python3
"""Run EODREPT against purpose-built fixtures and assert the printed report.

**Expected figures are written as explicit numbers**, the same rule the ACCTPOST harness follows. A
harness that recomputes a subtotal by summing the fixture in Python proves only that two
implementations share a bug.

The report is what an operator reads at 03:00, so the assertions are about what is actually on the
page: how many detail lines a page holds, that page two carries its own header, where each field
sits, and that the totals reconcile.

Run: python3 mainframe/cobol/test-eodrept.py
"""

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "mainframe" / "data"))

from comp3 import encode_comp3  # noqa: E402

COPYBOOK = REPO / "mainframe" / "copybook"
SOURCE = REPO / "mainframe" / "cobol" / "EODREPT.CBL"

BUS_DATE = "20260818"
RUN_TS = "20260818031500"

# A page is 60 lines: six of header, leaving 54 for the body. Written out here because the harness
# asserts the number rather than deriving it from the program - deriving it would make the
# off-by-one at the page boundary invisible, and that is the defect this shape of program has.
DETAIL_LINES_PER_PAGE = 54
FORM_FEED = "\f"


def text(value, width):
    return value.encode("ascii").ljust(width, b" ")


def number(value, width):
    return str(value).encode("ascii").rjust(width, b"0")


def acctrec(ref, customer="CU0000000001", acct_type="LIABILITY", currency="PLN", status="OPEN",
            booked=0, available=None, opened=20200101, last_move=20260818):
    available = booked if available is None else available
    record = (text(ref, 16) + text(customer, 12) + text(acct_type, 9) + text(currency, 3)
              + text(status, 7) + encode_comp3(booked) + encode_comp3(available)
              + number(opened, 8) + number(last_move, 8) + b" " * 21)
    assert len(record) == 100
    return record


def rejrec(movement_ref="TB00000000000009", code="R001", reason="ACCOUNT NOT FOUND IN MASTER"):
    """REJREC as ACCTPOST writes it: the movement verbatim in the first 120 bytes, then the reason."""
    movement = (text("TB202608180000000001", 20) + number(1, 2) + text(movement_ref, 16)
                + text("C", 1) + text("PLN", 3) + encode_comp3(10_00) + number(20260818, 8)
                + number(20260818090000, 14) + text("HARNESS FIXTURE", 35) + b" " * 13)
    record = movement + text(code, 4) + text(reason, 40) + number(20260818031500, 14) + b" " * 22
    assert len(record) == 200
    return record


def compile_program(binary):
    """Compiles, and treats any compiler output as a failure.

    The harness captures cobc's output so a clean run stays readable, which means a warning is
    invisible unless it is checked for. One slipped through exactly that way: arithmetic inside an
    IF condition, warned about under -Warithmetic-osvs, seen only when the compile was run by hand.
    """
    result = subprocess.run(
        ["cobc", "-x", "-std=ibm", "-Wall", "-I", str(COPYBOOK), "-o", str(binary), str(SOURCE)],
        check=True, capture_output=True, text=True,
    )
    noise = (result.stdout + result.stderr).strip()
    if noise:
        raise SystemExit(f"cobc is not silent under -Wall:\n{noise}")




def run(binary, master, rejects=(), ctl_rejected=None):
    """Returns (report text, stdout, return code)."""
    work = pathlib.Path(tempfile.mkdtemp())
    (work / "ACCTRPT.DAT").write_bytes(b"".join(master))
    (work / "REJECTS.DAT").write_bytes(b"".join(rejects))

    env = dict(os.environ, EODREPT_BUS_DATE=BUS_DATE, EODREPT_RUN_TS=RUN_TS)
    if ctl_rejected is not None:
        env["EODREPT_CTL_REJECTED"] = str(ctl_rejected)
    result = subprocess.run([str(binary)], cwd=work, env=env, capture_output=True, text=True)

    report = (work / "EODREPT.TXT").read_text()
    shutil.rmtree(work)
    return report, result.stdout, result.returncode


def pages(report):
    """The report split on the form feed the printer acts on."""
    return [page.splitlines() for page in report.split(FORM_FEED) if page.strip()]


def detail_lines(page):
    """Detail lines start with an account reference in column 3."""
    return [line for line in page if line[2:4] == "TB"]


def subtotal_lines(page):
    """Currency subtotal lines announce themselves."""
    return [line for line in page if "CURRENCY TOTAL" in line]


def line_starting(report, prefix):
    """The one line whose content begins with prefix, or a Failure if there is not exactly one."""
    found = [line for page in pages(report) for line in page if line.strip().startswith(prefix)]
    if len(found) != 1:
        raise Failure(f"{len(found)} lines start with {prefix!r}, expected 1")
    return found[0]


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


# ------------------------------------------------------------------------------------------
# Scenarios. Each raises Failure on a mismatch.
# ------------------------------------------------------------------------------------------

def scenario_page_holds_fifty_four_detail_lines(binary):
    """55 accounts in one currency: 54 on page one, 1 on page two."""
    master = [acctrec(f"TB{i:014d}", booked=100_00) for i in range(1, 56)]
    report, _, rc = run(binary, master)

    body = pages(report)
    check(rc == 0, f"return code {rc}, expected 0")
    check(len(detail_lines(body[0])) == DETAIL_LINES_PER_PAGE,
          f"page 1 holds {len(detail_lines(body[0]))} detail lines, expected 54")
    check(len(detail_lines(body[1])) == 1,
          f"page 2 holds {len(detail_lines(body[1]))} detail lines, expected 1")


def scenario_every_page_carries_its_own_header(binary):
    """A printed page nobody can identify is a page an operator cannot file."""
    master = [acctrec(f"TB{i:014d}", booked=100_00) for i in range(1, 56)]
    report, _, _ = run(binary, master)
    body = pages(report)

    for index, page in enumerate(body[:2], start=1):
        check("TESSERA BANK" in page[0], f"page {index} has no institution line")
        check("END OF DAY ACCOUNT REPORT" in page[0], f"page {index} has no report title")
        check(f"PAGE {index:>4}" in page[0], f"page {index} header reads {page[0][-12:]!r}")
        check("2026-08-18" in page[1], f"page {index} does not carry the business date")
        check("ACCOUNT REFERENCE" in page[3], f"page {index} has no column headings")


def scenario_detail_line_sits_in_fixed_columns(binary):
    """The whole point of a fixed-width report: every field in the same place on every line."""
    master = [acctrec("TB00000000000001", customer="CU0000000042", acct_type="LIABILITY",
                      currency="PLN", status="OPEN", booked=1_234_567_89, available=1_000_000_00,
                      last_move=20260817)]
    report, _, _ = run(binary, master)
    line = detail_lines(pages(report)[0])[0]

    # Column positions, written out. A single expected string would say "the line is wrong"
    # without saying which field slipped, and a fixed-width report is read by column.
    for start, end, expected in [
        (2, 18, "TB00000000000001"),
        (20, 32, "CU0000000042"),
        (34, 43, "LIABILITY"),
        (45, 52, "OPEN   "),
        (54, 57, "PLN"),
        (59, 82, "          1,234,567.89 "),
        (84, 107, "          1,000,000.00 "),
        (109, 119, "2026-08-17"),
    ]:
        check(line[start:end] == expected,
              f"columns {start + 1}-{end} hold {line[start:end]!r}, expected {expected!r}")


def scenario_negative_balance_prints_a_trailing_sign(binary):
    """A minus nine columns from its digits is a defect on a printed page."""
    master = [acctrec("TB00000000000001", booked=-1_250_00)]
    report, _, _ = run(binary, master)
    line = detail_lines(pages(report)[0])[0]

    check("1,250.00-" in line, f"negative balance printed as {line[57:80]!r}")


def scenario_no_line_exceeds_132_columns(binary):
    """A line printer at 132 columns wraps anything longer, and a wrapped report is unreadable.

    Widest possible balance: PIC S9(13)V99 at its maximum, in both columns.

    Lines are counted per page, never across the whole file. At a page break the form feed
    *replaces* the newline - the printer treats it as terminate-line-and-advance-page - so
    stripping form feeds first splices the last line of one page onto the first of the next and
    reports a 241-column line that does not exist.
    """
    master = [acctrec(f"TB{i:014d}", booked=9_999_999_999_999_99) for i in range(1, 60)]
    report, _, _ = run(binary, master)

    for number, page in enumerate(pages(report), start=1):
        for line in page:
            check(len(line) <= 132, f"page {number} line is {len(line)} columns:\n  {line!r}")


def scenario_two_runs_produce_the_same_bytes(binary):
    """The business date comes from the job, never the wall clock, or the cycle is not reproducible."""
    master = [acctrec(f"TB{i:014d}", booked=100_00) for i in range(1, 10)]
    first, _, _ = run(binary, master)
    second, _, _ = run(binary, master)

    check(first == second, "two runs of the same input produced different reports")


def scenario_currency_subtotal_sums_the_currency(binary):
    """Three PLN accounts: 100.00 + 250.50 + 49.50 = 400.00. Written out, not calculated."""
    master = [acctrec("TB00000000000001", currency="PLN", booked=100_00),
              acctrec("TB00000000000002", currency="PLN", booked=250_50),
              acctrec("TB00000000000003", currency="PLN", booked=49_50)]
    report, _, _ = run(binary, master)

    totals = [line for page in pages(report) for line in subtotal_lines(page)]
    check(len(totals) == 1, f"{len(totals)} subtotal lines, expected 1")
    check("PLN" in totals[0], f"subtotal names no currency: {totals[0]!r}")
    check("3 ACCOUNTS" in totals[0], f"subtotal counts wrong: {totals[0]!r}")
    check(totals[0].count("400.00") == 2,
          f"expected booked and available both 400.00: {totals[0]!r}")


def scenario_each_currency_starts_a_new_page(binary):
    """A currency spilling onto the previous currency's page is unfileable."""
    master = [acctrec("TB00000000000001", currency="EUR", booked=10_00),
              acctrec("TB00000000000002", currency="PLN", booked=20_00),
              acctrec("TB00000000000003", currency="USD", booked=30_00)]
    report, _, _ = run(binary, master)
    # The closing recap pages carry no detail lines, so they are not currency pages.
    body = [page for page in pages(report) if detail_lines(page)]

    check(len(body) == 3, f"{len(body)} pages hold accounts, expected 3 - one per currency")
    for index, currency in enumerate(["EUR", "PLN", "USD"]):
        details = detail_lines(body[index])
        check(len(details) == 1, f"page {index + 1} holds {len(details)} accounts, expected 1")
        check(details[0][54:57] == currency,
              f"page {index + 1} shows {details[0][54:57]!r}, expected {currency!r}")
        check(currency in subtotal_lines(body[index])[0],
              f"page {index + 1} subtotal is not the {currency} one")


def scenario_last_currency_subtotal_prints_at_end_of_file(binary):
    """No record follows the last account to trigger its break. This is the defect this shape has."""
    master = [acctrec("TB00000000000001", currency="EUR", booked=10_00),
              acctrec("TB00000000000002", currency="USD", booked=77_25)]
    report, _, _ = run(binary, master)

    totals = [line for page in pages(report) for line in subtotal_lines(page)]
    check(len(totals) == 2, f"{len(totals)} subtotals, expected 2 - the last one is missing")
    check("USD" in totals[1] and "77.25" in totals[1],
          f"final subtotal is {totals[1]!r}, expected USD 77.25")


def scenario_subtotal_totals_booked_and_available_separately(binary):
    """Two columns, two sums. Reusing one accumulator for both is a plausible-looking error."""
    master = [acctrec("TB00000000000001", currency="PLN", booked=100_00, available=60_00),
              acctrec("TB00000000000002", currency="PLN", booked=100_00, available=40_00)]
    report, _, _ = run(binary, master)
    line = subtotal_lines(pages(report)[0])[0]

    check(line[57:82].strip() == "200.00", f"booked total is {line[57:82]!r}, expected 200.00")
    check(line[82:107].strip() == "100.00", f"available total is {line[82:107]!r}, expected 100.00")


def scenario_subtotal_carries_a_negative_currency_total(binary):
    """-1,250.00 + 250.00 = -1,000.00, and the sign must survive the sum."""
    master = [acctrec("TB00000000000001", currency="PLN", booked=-1_250_00),
              acctrec("TB00000000000002", currency="PLN", booked=250_00)]
    report, _, _ = run(binary, master)
    line = subtotal_lines(pages(report)[0])[0]

    check("1,000.00-" in line, f"subtotal reads {line!r}, expected 1,000.00-")


def scenario_currency_recap_repeats_every_currency(binary):
    """The subtotals are pages apart. The recap is the one page that shows them together."""
    master = [acctrec("TB00000000000001", currency="EUR", booked=10_00),
              acctrec("TB00000000000002", currency="PLN", booked=20_00),
              acctrec("TB00000000000003", currency="PLN", booked=30_00),
              acctrec("TB00000000000004", currency="USD", booked=40_00)]
    report, _, _ = run(binary, master)

    recap = [page for page in pages(report) if any("CURRENCY RECAP" in line for line in page)]
    check(len(recap) == 1, f"{len(recap)} currency recap pages, expected 1")
    rows = [line for line in recap[0] if line[6:9] in ("EUR", "PLN", "USD")]
    check(len(rows) == 3, f"{len(rows)} recap rows, expected 3")
    check(rows[1][6:9] == "PLN" and "50.00" in rows[1],
          f"the PLN row reads {rows[1]!r}, expected 2 accounts totalling 50.00")


def scenario_grand_total_is_a_count_and_says_so(binary):
    """100 PLN plus 100 EUR is not 200 of anything. The report must refuse to add them."""
    master = [acctrec("TB00000000000001", currency="EUR", booked=100_00),
              acctrec("TB00000000000002", currency="PLN", booked=100_00)]
    report, _, _ = run(binary, master)

    grand = line_starting(report, "*** GRAND TOTAL")
    check("2 ACCOUNTS" in grand, f"grand total reads {grand!r}, expected 2 ACCOUNTS")
    check("200.00" not in grand, f"the grand total added two currencies together: {grand!r}")
    check("NO CROSS-CURRENCY AMOUNT IS PRINTED" in report,
          "the report does not say why there is no cross-currency total")


def scenario_reject_recap_counts_by_reason_code(binary):
    """Reason text comes from the reject record, so the report never restates WP-04's codes."""
    master = [acctrec("TB00000000000001", booked=100_00)]
    rejects = [rejrec(code="R001", reason="ACCOUNT NOT FOUND IN MASTER"),
               rejrec(code="R004", reason="CURRENCY SCALE NOT SUPPORTED"),
               rejrec(code="R001", reason="ACCOUNT NOT FOUND IN MASTER")]
    report, _, _ = run(binary, master, rejects)

    r001 = line_starting(report, "R001")
    r004 = line_starting(report, "R004")
    check(r001.strip().endswith("2"), f"R001 count is wrong: {r001!r}")
    check("ACCOUNT NOT FOUND IN MASTER" in r001, f"R001 carries no reason text: {r001!r}")
    check(r004.strip().endswith("1"), f"R004 count is wrong: {r004!r}")
    check(line_starting(report, "TOTAL REJECTED").strip().endswith("3"),
          "the reject total is not 3")


def scenario_reject_count_agrees_with_acctpost(binary):
    master = [acctrec("TB00000000000001", booked=100_00)]
    rejects = [rejrec(), rejrec()]
    report, _, rc = run(binary, master, rejects, ctl_rejected=2)

    check("*** IN BALANCE" in report, "the report does not confirm it reconciles")
    check(rc == 0, f"return code {rc}, expected 0")


def scenario_reject_count_mismatch_fails_the_step(binary):
    """A report that cannot be reconciled against its run must not pass silently."""
    master = [acctrec("TB00000000000001", booked=100_00)]
    rejects = [rejrec(), rejrec()]
    report, stdout, rc = run(binary, master, rejects, ctl_rejected=9)

    check("*** OUT OF BALANCE" in report, "a mismatch printed no out-of-balance line")
    check(rc == 12, f"return code {rc}, expected 12 - the cycle must abort on this")
    check("EODREPT CTL OUT-OF-BALANCE" in stdout, f"nothing on the job log: {stdout!r}")


def scenario_control_total_absent_is_stated_not_assumed(binary):
    """Never print a reconciliation that was not performed."""
    master = [acctrec("TB00000000000001", booked=100_00)]
    report, _, rc = run(binary, master, [rejrec()])

    check("NOT SUPPLIED" in report, "an absent control total was not reported as absent")
    check("IN BALANCE" not in report, "the report claimed a reconciliation it did not do")
    check(rc == 0, f"return code {rc}, expected 0 - absent is not a failure")


def scenario_empty_master_still_prints_its_recap(binary):
    """A day with nothing to report still produces a report. Silence is not evidence."""
    report, _, rc = run(binary, [])

    check(line_starting(report, "*** GRAND TOTAL").strip().endswith("0 ACCOUNTS"),
          "an empty master did not report zero accounts")
    check(rc == 0, f"return code {rc}, expected 0")


SCENARIOS = [value for name, value in sorted(globals().items()) if name.startswith("scenario_")]


def main() -> int:
    binary = pathlib.Path(tempfile.mkdtemp()) / "eodrept"
    print(f"Compiling {SOURCE.relative_to(REPO)} with -std=ibm\n")
    compile_program(binary)

    failures = 0
    for scenario in SCENARIOS:
        name = scenario.__name__.replace("scenario_", "").replace("_", " ")
        try:
            scenario(binary)
            print(f"  PASS  {name}")
        except Failure as error:
            failures += 1
            print(f"  FAIL  {name}\n        {error}")
        except Exception as error:  # noqa: BLE001 - one broken scenario must not hide the rest
            failures += 1
            print(f"  FAIL  {name}\n        unexpected {type(error).__name__}: {error}")

    print()
    if failures:
        print(f"{failures} of {len(SCENARIOS)} scenarios failed")
        return 1
    print(f"OK    {len(SCENARIOS)} scenarios passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
