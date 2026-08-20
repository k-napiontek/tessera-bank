"""Reading the ledger as at a position, without being able to write to it.

**The position is an `audit_record.seq`**, per
[ADR 0009](../../../../docs/governance/adr/0009-reports-are-cut-at-an-audit-position.md).
``JdbcAuditLog`` takes ``pg_advisory_xact_lock`` before reading the chain head and holds it to
commit, so audit appends cannot interleave: if seq P is visible then every seq below it has
committed, which is what a high-water mark has to mean. ``max(posting.id)`` and
``journal_entry.created_at`` both look like they would do and neither does - an identity value is
allocated at insert and ``now()`` is fixed at transaction start, so a transaction that begins early
and commits late carries a low value while appearing after rows written later. A rerun would then
admit rows the first run could not see, and the morning's break report would be irreproducible in a
way nothing would flag.

**Balances are summed from postings, never read from `balance`.** The materialised figure reflects
now, and a reconciliation built on it would be comparing the mainframe against a cache of the thing
it is supposed to be checking.

**Two figures come back per account, not one.**

* ``booked_minor`` is the ledger's own balance at the position: everything posted.
* ``expected_minor`` is what the account master *ought* to hold, which is a smaller set. A posting
  belongs to it when the overnight cycle had already seen it - either its transfer reference is in
  the movement file the cycle consumed, or its value date is earlier than the business date, in
  which case an earlier cycle applied it.

The difference between the two is precisely the movements posted after the cut-off, and having both
is what lets ``compare`` say *timing* where a single figure could only say *drift*. See ADR 0015.
"""

from __future__ import annotations

import datetime as dt
from collections.abc import Collection
from dataclasses import dataclass
from types import TracebackType
from typing import Final

import psycopg

from recon.accounting import signed

__all__ = ["ENTRY_ACTIONS", "GENESIS_HASH", "LedgerAccount", "LedgerReader", "Position"]

#: The predecessor of the first audit row, and the chain hash of a ledger nothing has happened in.
#: Defined identically in AuditEntry.GENESIS_HASH.
GENESIS_HASH: Final = "0" * 64

#: The audit actions that accompany a journal entry. Anything else - an account opening, a hold
#: transition - is about a subject that is not an entry.
ENTRY_ACTIONS: Final = ["TRANSFER_POSTED", "TRANSFER_REVERSED"]


@dataclass(frozen=True, slots=True)
class Position:
    """How far along the audit chain a run read, and which chain it was."""

    seq: int
    chain_hash: str


@dataclass(frozen=True, slots=True)
class LedgerAccount:
    """One account as the ledger holds it, cut at a position."""

    account_ref: str
    account_type: str
    currency: str
    status: str
    booked_minor: int
    expected_minor: int


class LedgerReader:
    """A read-only view of the ledger. Holds a connection; close it with the context manager."""

    def __init__(self, connection: psycopg.Connection, *, query_timeout_seconds: int) -> None:
        self._connection = connection
        # A reconciliation that hangs at 06:00 is a reconciliation nobody has. Bounded here rather
        # than left to the server's default, which is usually none at all.
        self._connection.execute(f"SET statement_timeout = {int(query_timeout_seconds) * 1000}")

    def __enter__(self) -> LedgerReader:
        return self

    def __exit__(
        self,
        kind: type[BaseException] | None,
        value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self._connection.close()

    def resolve_position(self, seq: int | None = None) -> Position:
        """The current high-water mark, or the recorded one being reproduced."""
        if seq is None:
            row = self._connection.execute(
                "SELECT seq, hash FROM audit_record ORDER BY seq DESC LIMIT 1"
            ).fetchone()
            if row is None:
                return Position(seq=0, chain_hash=GENESIS_HASH)
            return Position(seq=int(row[0]), chain_hash=str(row[1]))

        row = self._connection.execute(
            "SELECT seq, hash FROM audit_record WHERE seq = %s", (seq,)
        ).fetchone()
        if row is None:
            raise LookupError(
                f"this ledger has no audit record at position {seq}; the run that recorded it was "
                f"cut against a different database"
            )
        return Position(seq=int(row[0]), chain_hash=str(row[1]))

    def accounts_as_at(
        self,
        business_date: dt.date,
        position: Position,
        applied_refs: Collection[str],
    ) -> list[LedgerAccount]:
        """Every account open within ``position``, with both balances, ascending by reference.

        ``applied_refs`` is the cut-off: the transfer references the movement file carried into
        tonight's cycle. It may be empty, and an empty set is meaningful rather than a default - it
        says the cycle applied nothing, which makes every one of today's movements timing.
        """
        rows = self._connection.execute(
            """
            SELECT a.reference,
                   a.account_type,
                   a.currency,
                   a.status,
                   coalesce(sum(m.amount_minor) FILTER (WHERE m.direction = 'DEBIT'), 0),
                   coalesce(sum(m.amount_minor) FILTER (WHERE m.direction = 'CREDIT'), 0),
                   coalesce(sum(m.amount_minor) FILTER (
                       WHERE m.direction = 'DEBIT' AND m.at_the_master), 0),
                   coalesce(sum(m.amount_minor) FILTER (
                       WHERE m.direction = 'CREDIT' AND m.at_the_master), 0)
              FROM account a
              JOIN audit_record opened
                ON opened.subject_ref = a.reference
               AND opened.action = 'ACCOUNT_OPENED'
               AND opened.seq <= %(position)s
              LEFT JOIN (
                   SELECT p.account_ref,
                          p.direction,
                          p.amount_minor,
                          (je.reference = ANY(%(applied)s) OR je.value_date < %(business_date)s)
                              AS at_the_master
                     FROM posting p
                     JOIN journal_entry je ON je.reference = p.entry_ref
                     JOIN audit_record ar
                       ON ar.subject_ref = je.reference
                      AND ar.action = ANY(%(actions)s)
                    WHERE je.value_date <= %(business_date)s
                      AND ar.seq <= %(position)s
              ) m ON m.account_ref = a.reference
             GROUP BY a.reference, a.account_type, a.currency, a.status
             ORDER BY a.reference
            """,
            {
                "business_date": business_date,
                "position": position.seq,
                "actions": ENTRY_ACTIONS,
                "applied": list(applied_refs),
            },
        ).fetchall()

        return [
            LedgerAccount(
                account_ref=str(row[0]),
                account_type=str(row[1]),
                currency=str(row[2]),
                status=str(row[3]),
                booked_minor=signed(str(row[1]), int(row[4]), int(row[5])),
                expected_minor=signed(str(row[1]), int(row[6]), int(row[7])),
            )
            for row in rows
        ]
