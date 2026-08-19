"""The regulatory extract, and its agreement with the layout declared in contracts/.

Two sides, deliberately. ``extract.py`` declares the offsets it writes at; the contract declares the
offsets a receiver reads at; this file parses the contract and asserts they are the same numbers.
Nothing is shared between them, which is the only reason it is a test rather than a restatement -
the same arrangement ``check-copybook-offsets.py`` uses for the copybooks, for the same reason.
"""

from __future__ import annotations

import datetime as dt
import pathlib
import re

import pytest

from reporting.extract import LAYOUT, RECORD_LENGTH, render
from reporting.ledger import AccountPosition, Position
from reporting.position_report import PositionReport

REPO = pathlib.Path(__file__).resolve().parents[3]
CONTRACT = REPO / "contracts" / "reporting" / "regulatory-extract-v1.md"

HEADING = re.compile(r"^##\s+(HDR|ACC|TRL)\b")
ROW = re.compile(
    r"^\|\s*`([A-Z0-9-]+)`\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*`PIC ([X9])\((\d+)\)`"
)

BUSINESS_DATE = dt.date(2026, 8, 18)
POSITION = Position(seq=4711, chain_hash="9f" * 32)
INSTITUTION = "TESSPLPWXXX"


def declared() -> dict[str, list[tuple[str, int, int, str]]]:
    """The layout as the contract states it: (name, start, end, picture kind) per record type."""
    found: dict[str, list[tuple[str, int, int, str]]] = {}
    current = None
    for line in CONTRACT.read_text(encoding="utf-8").splitlines():
        heading = HEADING.match(line)
        if heading:
            current = heading.group(1)
            found[current] = []
            continue
        row = ROW.match(line)
        if row and current:
            name, start, end, _length, kind, _width = row.groups()
            found[current].append((name, int(start), int(end), kind))
    return found


def account(reference: str, *, currency: str = "PLN", debit: int = 0, credit: int = 0):
    return AccountPosition(
        account_ref=reference,
        customer_ref="CUST0000000000000001",
        account_type="LIABILITY",
        currency=currency,
        status="OPEN",
        opened_date=dt.date(2026, 1, 1),
        debit_minor=debit,
        credit_minor=credit,
        movement_count=1 if debit or credit else 0,
    )


def report(*accounts) -> PositionReport:
    return PositionReport.of(BUSINESS_DATE, POSITION, list(accounts))


def pair(amount: int, currency: str = "PLN") -> tuple:
    """One account debited and one credited, so the report balances."""
    return (
        account("ACC-0000000001", currency=currency, debit=amount),
        account("ACC-0000000002", currency=currency, credit=amount),
    )


def field(record: str, name: str) -> str:
    """One field's bytes, sliced at the offsets the writer declares."""
    for declared_name, start, end, _kind in LAYOUT[record[:3]]:
        if declared_name == name:
            return record[start - 1 : end]
    raise KeyError(f"{name} is not a field of {record[:3]}")


def records(rendered: str) -> list[str]:
    return rendered.split("\n")[:-1]


def test_the_writer_agrees_with_the_declared_contract() -> None:
    assert LAYOUT == declared()


def test_every_record_is_the_declared_length() -> None:
    for record in records(render(report(*pair(100)), INSTITUTION)):
        assert len(record) == RECORD_LENGTH


def test_the_file_is_a_header_then_details_then_a_trailer() -> None:
    rendered = render(report(*pair(100)), INSTITUTION)

    assert [record[:3] for record in records(rendered)] == ["HDR", "ACC", "ACC", "TRL"]


def test_the_header_carries_the_position_and_the_chain_hash() -> None:
    header = records(render(report(), INSTITUTION))[0]

    assert field(header, "REGEXT-FORMAT-ID") == "TB-REGEXT-V1"
    assert field(header, "REGEXT-INSTITUTION") == INSTITUTION
    assert field(header, "REGEXT-BUSINESS-DATE") == "20260818"
    assert field(header, "REGEXT-POSITION") == "000000000000004711"
    assert field(header, "REGEXT-CHAIN-HASH") == POSITION.chain_hash


def test_a_detail_record_is_left_aligned_text_and_right_aligned_digits() -> None:
    detail = records(render(report(*pair(25_000)), INSTITUTION))[1]

    assert field(detail, "REGEXT-ACCT-REF") == "ACC-0000000001".ljust(34)
    assert field(detail, "REGEXT-ACCT-TYPE") == "LIABILITY".ljust(16)
    assert field(detail, "REGEXT-CURRENCY") == "PLN"
    assert field(detail, "REGEXT-CURRENCY-SCALE") == "2"
    assert field(detail, "REGEXT-OPENED-DATE") == "20260101"
    assert field(detail, "REGEXT-MOVEMENT-COUNT") == "0000000001"


def test_the_sign_is_its_own_column_so_the_digits_are_always_fixed_width() -> None:
    debited, credited = records(render(report(*pair(25_000)), INSTITUTION))[1:3]

    # ACC-0000000001 is debited, and a liability rises on the credit.
    assert field(debited, "REGEXT-BOOKED-SIGN") == "-"
    assert field(debited, "REGEXT-BOOKED-MINOR") == "000000000025000"
    assert field(credited, "REGEXT-BOOKED-SIGN") == "+"
    assert field(credited, "REGEXT-BOOKED-MINOR") == "000000000025000"


def test_a_zero_scale_currency_reports_its_own_scale() -> None:
    detail = records(render(report(*pair(700, currency="JPY")), INSTITUTION))[1]

    assert field(detail, "REGEXT-CURRENCY") == "JPY"
    assert field(detail, "REGEXT-CURRENCY-SCALE") == "0"


def test_the_trailer_totals_what_the_details_say() -> None:
    rendered = render(
        PositionReport.of(
            BUSINESS_DATE,
            POSITION,
            [
                account("ACC-0000000001", debit=25_000),
                account("ACC-0000000002", credit=25_000),
                account("ACC-0000000003", currency="JPY", debit=700),
                account("ACC-0000000004", currency="JPY", credit=700),
            ],
        ),
        INSTITUTION,
    )
    trailer = records(rendered)[-1]

    assert field(trailer, "REGEXT-ACCOUNT-COUNT") == "0000000004"
    # 25 000 + 25 000 + 700 + 700, absolute, across currencies: a control total, not an amount.
    assert field(trailer, "REGEXT-HASH-TOTAL") == "000000000000051400"
    assert field(trailer, "REGEXT-CURRENCY-COUNT") == "002"
    assert field(trailer, "REGEXT-BUSINESS-DATE") == "20260818"


def test_an_amount_too_wide_for_its_field_fails_the_run() -> None:
    with pytest.raises(OverflowError):
        render(report(*pair(10**15)), INSTITUTION)


def test_every_field_holds_what_its_picture_declares() -> None:
    # The strongest statement this format can make about personal data: it has no free-text field at
    # all. A PIC 9 field holds digits and a PIC X field holds an identifier or an enumeration, so a
    # name, an address or an email address cannot be represented, let alone leaked.
    rendered = render(report(*pair(25_000)), INSTITUTION)

    for record in records(rendered):
        for name, start, end, kind in LAYOUT[record[:3]]:
            value = record[start - 1 : end]
            if kind == "9":
                assert value.isdigit(), f"{record[:3]}.{name} is {value!r}, not digits"
            else:
                assert re.fullmatch(r"[A-Za-z0-9+\-]*\s*", value), (
                    f"{record[:3]}.{name} is {value!r}; a PIC X field carries an identifier or an "
                    f"enumeration, and anything with a space or punctuation in it is neither"
                )


def test_the_extract_carries_no_personal_data() -> None:
    # "No personal data" is a Definition of Done box, and a grep is the only thing that ticks it
    # honestly. The shapes are what this estate would leak if anything joined to the customer master
    # by mistake.
    rendered = render(report(*pair(25_000)), INSTITUTION)

    forbidden = {
        "email address": re.compile(r"[^\s@]+@[^\s@]+\.[^\s@]+"),
        "street address": re.compile(r"\d+\s+[A-Z][a-z]+\s+(Street|Road|Avenue|ul\.)"),
        "personal name": re.compile(r"\b[A-Z][a-z]+\s+[A-Z][a-z]+\b"),
    }
    for description, pattern in forbidden.items():
        assert not pattern.search(rendered), f"the extract looks like it contains a {description}"


def test_the_file_is_line_terminated_and_seekable_by_record_number() -> None:
    rendered = render(report(*pair(1)), INSTITUTION)

    # Record n begins at byte (n - 1) * (length + 1). That holds only if every record is the
    # declared width and every terminator is a single byte.
    assert rendered.endswith("\n")
    assert "\r" not in rendered
    for index, record in enumerate(records(rendered)):
        offset = index * (RECORD_LENGTH + 1)
        assert rendered[offset : offset + RECORD_LENGTH] == record
