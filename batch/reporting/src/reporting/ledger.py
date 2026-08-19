"""Reading the ledger as at a position, in one snapshot, without being able to write to it.

**The position is an audit sequence number.** ``audit_record.seq`` is the only column in this schema
where sequence order is *commit* order: ``JdbcAuditLog`` takes ``pg_advisory_xact_lock`` before
reading the chain head and holds it to commit, so appends cannot interleave. If seq P is visible
then every seq below it has committed, which is precisely what a high-water mark has to mean.

``max(posting.id)`` and ``journal_entry.created_at`` both look like they would do and neither does.
An identity value is allocated when the row is inserted and ``now()`` is fixed when the transaction
starts, so a transaction that begins early and commits late carries a low value and appears *after*
rows that were written later. A rerun would then admit rows the first run could not see, and the
report would be irreproducible in a way nothing would flag - the second run simply has more in it.

Every journal entry has exactly one audit row: ``Transfer`` records ``TRANSFER_POSTED`` inside the
same transaction as the postings, ``ReverseTransfer`` records ``TRANSFER_REVERSED``, and
``CaptureHold`` delegates its posting to ``Transfer`` rather than writing entries of its own. So
the join from an entry to its position is total, and an entry with no audit row is a defect in the
ledger rather than a case this module should tolerate.

Accounts are bounded the same way, by their ``ACCOUNT_OPENED`` row. An account created after the cut
would otherwise appear in a rerun with a zero balance, and the extract's record count would move for
a file that is supposed to be byte-identical.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from types import TracebackType
from typing import Final

import psycopg
from psycopg import sql

__all__ = [
    "GENESIS_HASH",
    "AccountPosition",
    "LedgerReader",
    "Movement",
    "Position",
]

#: The predecessor of the first audit row, and the chain hash of a ledger nothing has happened in.
#: Defined identically in AuditEntry.GENESIS_HASH.
GENESIS_HASH: Final = "0" * 64

#: The audit actions that accompany a journal entry. Anything else - an account opening, a hold
#: transition - is about a subject that is not an entry.
ENTRY_ACTIONS: Final = ["TRANSFER_POSTED", "TRANSFER_REVERSED"]


@dataclass(frozen=True, slots=True)
class Position:
    """Where in the ledger's history a run was cut.

    The hash is carried alongside the sequence so a report says which chain its figures came from,
    not merely how far along it. Two databases can both hold a row at seq 4711; only one can hold
    that row with that hash, so a file re-cut against a restored database whose history diverged is
    detectable rather than merely unlikely.
    """

    seq: int
    chain_hash: str


@dataclass(frozen=True, slots=True)
class AccountPosition:
    """One account's standing at a business date, summed from postings."""

    account_ref: str
    customer_ref: str
    account_type: str
    currency: str
    status: str
    opened_date: dt.date
    debit_minor: int
    credit_minor: int
    movement_count: int


@dataclass(frozen=True, slots=True)
class Movement:
    """One posting - one leg of one entry."""

    entry_ref: str
    seq: int
    account_ref: str
    account_type: str
    direction: str
    amount_minor: int
    currency: str
    value_date: dt.date


class LedgerReader:
    """A read-only, repeatable-read view of the ledger.

    Read-only because a reporting job that *can* write to the ledger is one that eventually will,
    and the damage is silent: a report that corrects what it is reporting on. Repeatable read
    because a run issues several queries and they must all see the same ledger - the position pins
    which rows are in scope, and the snapshot pins that both were read from one state.
    """

    def __init__(self, connection: psycopg.Connection, *, query_timeout_seconds: int) -> None:
        self._connection = connection
        self._connection.read_only = True
        self._connection.isolation_level = psycopg.IsolationLevel.REPEATABLE_READ
        self._connection.autocommit = False
        # A reporting query that has not returned in five minutes is scanning something it should be
        # seeking. Failing at 02:05 naming the query beats hanging until somebody arrives.
        # SET takes no parameters, so the value is composed rather than bound. sql.Literal
        # quotes it; the value is an int the configuration has already validated.
        self._connection.execute(
            sql.SQL("SET statement_timeout = {}").format(sql.Literal(f"{query_timeout_seconds}s"))
        )

    def __enter__(self) -> LedgerReader:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self._connection.close()

    def resolve_position(self, seq: int | None = None) -> Position:
        """The current high-water mark, or the recorded one being reproduced.

        A ``seq`` that no audit row carries raises: a run asked to reproduce a cut this database
        does not contain must fail loudly, because the alternative is a plausible file for a
        different ledger.
        """
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

    def accounts_as_at(self, business_date: dt.date, position: Position) -> list[AccountPosition]:
        """Every account open by ``business_date`` and within ``position``, with its posting totals.

        The totals are debits and credits, not a balance. Which way an account moves depends on the
        normal balance of its type, and that is an accounting rule rather than a SQL one - see
        ``accounting.py``. Keeping it out of the query means it can be tested without a database and
        stated exactly once.
        """
        rows = self._connection.execute(
            """
            SELECT a.reference,
                   a.customer_ref,
                   a.account_type,
                   a.currency,
                   a.status,
                   a.opened_date,
                   coalesce(sum(m.amount_minor) FILTER (WHERE m.direction = 'DEBIT'), 0),
                   coalesce(sum(m.amount_minor) FILTER (WHERE m.direction = 'CREDIT'), 0),
                   count(m.amount_minor)
              FROM account a
              JOIN audit_record opened
                ON opened.subject_ref = a.reference
               AND opened.action = 'ACCOUNT_OPENED'
               AND opened.seq <= %(position)s
              LEFT JOIN (
                   SELECT p.account_ref, p.direction, p.amount_minor
                     FROM posting p
                     JOIN journal_entry je ON je.reference = p.entry_ref
                     JOIN audit_record ar
                       ON ar.subject_ref = je.reference
                      AND ar.action = ANY(%(actions)s)
                    WHERE je.value_date <= %(business_date)s
                      AND ar.seq <= %(position)s
              ) m ON m.account_ref = a.reference
             WHERE a.opened_date <= %(business_date)s
             GROUP BY a.reference, a.customer_ref, a.account_type, a.currency, a.status,
                      a.opened_date
             ORDER BY a.reference
            """,
            {
                "business_date": business_date,
                "position": position.seq,
                "actions": ENTRY_ACTIONS,
            },
        ).fetchall()

        return [
            AccountPosition(
                account_ref=row[0],
                customer_ref=row[1],
                account_type=row[2],
                currency=row[3],
                status=row[4],
                opened_date=row[5],
                debit_minor=int(row[6]),
                credit_minor=int(row[7]),
                movement_count=int(row[8]),
            )
            for row in rows
        ]

    def movements_on(self, business_date: dt.date, position: Position) -> list[Movement]:
        """Every posting whose entry has ``business_date`` as its value date, within ``position``.

        Ordered by entry reference and leg, which is a total order - ``posting_seq_uq`` makes the
        pair unique - so two runs cannot produce the same rows in a different sequence. An ordering
        that is merely usually stable produces a file that is merely usually reproducible.
        """
        rows = self._connection.execute(
            """
            SELECT p.entry_ref,
                   p.seq,
                   p.account_ref,
                   a.account_type,
                   p.direction,
                   p.amount_minor,
                   p.currency,
                   je.value_date
              FROM posting p
              JOIN journal_entry je ON je.reference = p.entry_ref
              JOIN account a ON a.reference = p.account_ref
              JOIN audit_record ar
                ON ar.subject_ref = je.reference
               AND ar.action = ANY(%(actions)s)
               AND ar.seq <= %(position)s
             WHERE je.value_date = %(business_date)s
             ORDER BY p.entry_ref, p.seq
            """,
            {
                "business_date": business_date,
                "position": position.seq,
                "actions": ENTRY_ACTIONS,
            },
        ).fetchall()

        return [
            Movement(
                entry_ref=row[0],
                seq=int(row[1]),
                account_ref=row[2],
                account_type=row[3],
                direction=row[4],
                amount_minor=int(row[5]),
                currency=row[6],
                value_date=row[7],
            )
            for row in rows
        ]

    def materialised_balances(self) -> dict[str, int]:
        """The ledger's own ``balance`` rows.

        **No report is built from this.** It reflects now rather than a position, so a report
        using it would change under its own feet. It is here so the reconciliation can compare the
        report's arithmetic against the ledger's own figure, which is only an independent check for
        as long as the two are computed separately.
        """
        rows = self._connection.execute(
            "SELECT account_ref, booked_minor FROM balance ORDER BY account_ref"
        ).fetchall()
        return {row[0]: int(row[1]) for row in rows}

    def execute_for_test(self, sql: str) -> None:
        """Run a statement through this connection. Used only to prove it is read-only."""
        self._connection.execute(sql)
