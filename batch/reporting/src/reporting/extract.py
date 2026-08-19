"""The regulatory extract: `TB-REGEXT-V1`, fixed-width, 200-byte records.

The layout below is the writer's own statement of where each field goes. The receiver's statement
is ``contracts/reporting/regulatory-extract-v1.md``, and a test parses that document and asserts
the two agree. Neither side reads the other at run time: a program that read its format out of
the contract would agree with it by construction, and by construction is not the same as by check.

Why a 2025 component writes a fixed-width file is answered in the contract. The short version is
that this is what a regulatory submission is, and the format's boringness is the feature - a record
either is 200 bytes or is rejected.
"""

from __future__ import annotations

from typing import Final

from reporting.accounting import booked_balance
from reporting.money import Money
from reporting.position_report import PositionReport

__all__ = ["FORMAT_ID", "LAYOUT", "RECORD_LENGTH", "render"]

FORMAT_ID: Final = "TB-REGEXT-V1"
RECORD_LENGTH: Final = 200

#: (name, start, end, picture kind), 1-based and inclusive, per record type. Transcribed from the
#: contract; the test asserts the transcription is faithful.
LAYOUT: Final[dict[str, list[tuple[str, int, int, str]]]] = {
    "HDR": [
        ("REGEXT-REC-TYPE", 1, 3, "X"),
        ("REGEXT-FORMAT-ID", 4, 15, "X"),
        ("REGEXT-INSTITUTION", 16, 26, "X"),
        ("REGEXT-BUSINESS-DATE", 27, 34, "9"),
        ("REGEXT-POSITION", 35, 52, "9"),
        ("REGEXT-CHAIN-HASH", 53, 116, "X"),
        ("FILLER", 117, 200, "X"),
    ],
    "ACC": [
        ("REGEXT-REC-TYPE", 1, 3, "X"),
        ("REGEXT-ACCT-REF", 4, 37, "X"),
        ("REGEXT-CUST-REF", 38, 71, "X"),
        ("REGEXT-ACCT-TYPE", 72, 87, "X"),
        ("REGEXT-CURRENCY", 88, 90, "X"),
        ("REGEXT-STATUS", 91, 106, "X"),
        ("REGEXT-BOOKED-SIGN", 107, 107, "X"),
        ("REGEXT-BOOKED-MINOR", 108, 122, "9"),
        ("REGEXT-CURRENCY-SCALE", 123, 123, "9"),
        ("REGEXT-OPENED-DATE", 124, 131, "9"),
        ("REGEXT-MOVEMENT-COUNT", 132, 141, "9"),
        ("FILLER", 142, 200, "X"),
    ],
    "TRL": [
        ("REGEXT-REC-TYPE", 1, 3, "X"),
        ("REGEXT-ACCOUNT-COUNT", 4, 13, "9"),
        ("REGEXT-HASH-TOTAL", 14, 31, "9"),
        ("REGEXT-CURRENCY-COUNT", 32, 34, "9"),
        ("REGEXT-BUSINESS-DATE", 35, 42, "9"),
        ("FILLER", 43, 200, "X"),
    ],
}


class _Record:
    """A record built by writing named fields into a fixed-width buffer.

    Fields are placed by name at the offsets ``LAYOUT`` declares rather than concatenated in
    order. Concatenation is how a fixed-width writer drifts: one field's width changes, every field
    after it moves, and the file still looks like a file.
    """

    def __init__(self, record_type: str) -> None:
        self._type = record_type
        self._fields = {name: (start, end, kind) for name, start, end, kind in LAYOUT[record_type]}
        self._buffer = [" "] * RECORD_LENGTH
        self.text("REGEXT-REC-TYPE", record_type)

    def text(self, name: str, value: str) -> _Record:
        """A PIC X field: left-aligned, space-padded."""
        start, end, _kind = self._require(name, "X")
        width = end - start + 1
        if len(value) > width:
            raise OverflowError(
                f"{self._type}.{name} holds {width} characters, {value!r} needs {len(value)}"
            )
        self._place(start, value.ljust(width))
        return self

    def digits(self, name: str, value: int) -> _Record:
        """A PIC 9 field: right-aligned, zero-padded, never negative."""
        start, end, _kind = self._require(name, "9")
        width = end - start + 1
        if value < 0:
            raise ValueError(f"{self._type}.{name} is unsigned; {value} carries a sign")
        rendered = str(value)
        if len(rendered) > width:
            raise OverflowError(
                f"{self._type}.{name} holds {width} digits, {value} needs {len(rendered)}"
            )
        self._place(start, rendered.rjust(width, "0"))
        return self

    def money(self, sign_field: str, digits_field: str, amount: Money) -> _Record:
        """An amount, as a sign in one column and fixed-width digits in another."""
        start, end, _kind = self._require(digits_field, "9")
        sign, rendered = amount.to_fixed(end - start + 1)
        self.text(sign_field, sign)
        self._place(start, rendered)
        return self

    def _require(self, name: str, kind: str) -> tuple[int, int, str]:
        start, end, declared = self._fields[name]
        if declared != kind:
            raise TypeError(f"{self._type}.{name} is PIC {declared}, written as PIC {kind}")
        return start, end, declared

    def _place(self, start: int, value: str) -> None:
        self._buffer[start - 1 : start - 1 + len(value)] = list(value)

    def __str__(self) -> str:
        return "".join(self._buffer)


def render(report: PositionReport, institution: str) -> str:
    """The extract for one position report, as a line-terminated fixed-width file.

    Takes the position report rather than raw accounts, so the extract and the position report can
    never disagree about what was in scope: they are two renderings of one set of rows, and the
    control totals below are computed from the same accounts the CSV listed.
    """
    header = (
        _Record("HDR")
        .text("REGEXT-FORMAT-ID", FORMAT_ID)
        .text("REGEXT-INSTITUTION", institution)
        .digits("REGEXT-BUSINESS-DATE", int(report.business_date_ccyymmdd))
        .digits("REGEXT-POSITION", report.position.seq)
        .text("REGEXT-CHAIN-HASH", report.position.chain_hash)
    )

    details = []
    hash_total = 0
    for account in report.accounts:
        booked = booked_balance(account)
        hash_total += abs(booked.minor)
        details.append(
            _Record("ACC")
            .text("REGEXT-ACCT-REF", account.account_ref)
            .text("REGEXT-CUST-REF", account.customer_ref)
            .text("REGEXT-ACCT-TYPE", account.account_type)
            .text("REGEXT-CURRENCY", account.currency)
            .text("REGEXT-STATUS", account.status)
            .money("REGEXT-BOOKED-SIGN", "REGEXT-BOOKED-MINOR", booked)
            .digits("REGEXT-CURRENCY-SCALE", booked.scale)
            .digits("REGEXT-OPENED-DATE", int(account.opened_date.strftime("%Y%m%d")))
            .digits("REGEXT-MOVEMENT-COUNT", account.movement_count)
        )

    trailer = (
        _Record("TRL")
        .digits("REGEXT-ACCOUNT-COUNT", len(report.accounts))
        # A control total, not an amount: absolute minor units summed across currencies, which no
        # exchange rate is needed to recompute and which any lost or duplicated record changes.
        .digits("REGEXT-HASH-TOTAL", hash_total)
        .digits("REGEXT-CURRENCY-COUNT", len({account.currency for account in report.accounts}))
        .digits("REGEXT-BUSINESS-DATE", int(report.business_date_ccyymmdd))
    )

    return "".join(f"{record}\n" for record in [header, *details, trailer])
