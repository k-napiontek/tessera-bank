"""A real PostgreSQL with the ledger's own schema in it.

The migrations are applied from
``services/ledger-persistence/src/main/resources/db/migration``, in order, exactly as Flyway would.
Transcribing the schema into a test fixture would produce a reader verified against a fiction: it
would keep passing on the day WP-07 adds a column, and the first thing to notice would be a
production run. Reading the real files means this suite fails the moment the ledger's shape moves,
which is the entire point of a contract test pointed at a database.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
from collections.abc import Iterator

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

REPO = pathlib.Path(__file__).resolve().parents[3]
MIGRATIONS = (
    REPO / "services" / "ledger-persistence" / "src" / "main" / "resources" / "db" / "migration"
)

GENESIS_HASH = "0" * 64


@pytest.fixture(scope="session")
def dsn() -> Iterator[str]:
    """A PostgreSQL with the ledger schema, shared by the suite."""
    with PostgresContainer("postgres:16-alpine") as container:
        url = container.get_connection_url().replace("postgresql+psycopg2://", "postgresql://")
        with psycopg.connect(url, autocommit=True) as connection:
            _migrate(connection)
        yield url


def _migrate(connection: psycopg.Connection) -> None:
    files = sorted(MIGRATIONS.glob("V*.sql"), key=lambda path: int(path.name.split("__")[0][1:]))
    if not files:
        raise RuntimeError(f"no ledger migrations found under {MIGRATIONS}")
    for path in files:
        # No parameters, so psycopg uses the simple query protocol and the whole file -
        # dollar-quoted function bodies included - goes to the server as one script, as Flyway does.
        connection.execute(path.read_text(encoding="utf-8"))


@pytest.fixture
def ledger(dsn: str) -> Iterator[Ledger]:
    """An empty ledger, truncated between tests."""
    with psycopg.connect(dsn, autocommit=True) as connection:
        _empty(connection)
        yield Ledger(connection)
        _empty(connection)


def _empty(connection: psycopg.Connection) -> None:
    # posting and audit_record both refuse DELETE and TRUNCATE by trigger - that is what makes them
    # append-only, and it is a control worth not weakening even in a test. Dropping the triggers for
    # the duration of the wipe is honest about what is being suspended and restores it immediately.
    connection.execute("ALTER TABLE posting DISABLE TRIGGER posting_no_update")
    connection.execute("ALTER TABLE audit_record DISABLE TRIGGER audit_record_no_change")
    connection.execute("ALTER TABLE audit_record DISABLE TRIGGER audit_record_no_truncate")
    connection.execute(
        "TRUNCATE audit_record, posting, hold, balance, journal_entry, account RESTART IDENTITY"
    )
    connection.execute("ALTER TABLE posting ENABLE TRIGGER posting_no_update")
    connection.execute("ALTER TABLE audit_record ENABLE TRIGGER audit_record_no_change")
    connection.execute("ALTER TABLE audit_record ENABLE TRIGGER audit_record_no_truncate")


class Ledger:
    """Writes the rows the ledger's Java would write, so the reader has something true to read.

    It reproduces the ordering that matters and nothing else. In particular the audit hashes are a
    plausible chain rather than the canonical encoding ``AuditEntry`` defines: reporting depends
    on audit *order*, which the advisory lock guarantees, and not on the chain verifying.
    Reimplementing the encoding here would create a second definition of it, and the day the two
    disagreed this suite would be asserting against the copy.
    """

    def __init__(self, connection: psycopg.Connection) -> None:
        self._connection = connection

    def open_account(
        self,
        reference: str,
        *,
        customer_ref: str = "CUST0000000000000001",
        account_type: str = "LIABILITY",
        currency: str = "PLN",
        status: str = "OPEN",
        opened_date: str = "2026-01-01",
    ) -> int:
        self._connection.execute(
            """
            INSERT INTO account (reference, customer_ref, account_type, currency, status,
                                 opened_date, overdraft_limit_minor)
            VALUES (%s, %s, %s, %s, %s, %s, NULL)
            """,
            (reference, customer_ref, account_type, currency, status, opened_date),
        )
        self._connection.execute(
            "INSERT INTO balance (account_ref, booked_minor, currency) VALUES (%s, 0, %s)",
            (reference, currency),
        )
        return self._audit("ACCOUNT_OPENED", reference, {"status": status})

    def post_transfer(
        self,
        reference: str,
        *,
        debit: str,
        credit: str,
        amount_minor: int,
        currency: str = "PLN",
        value_date: str,
        reference_text: str | None = None,
        action: str = "TRANSFER_POSTED",
    ) -> int:
        """Both legs, both balances and the audit row, in one transaction - as Transfer does."""
        with self._connection.transaction():
            self._connection.execute(
                """
                INSERT INTO journal_entry (reference, value_date, currency, reference_text)
                VALUES (%s, %s, %s, %s)
                """,
                (reference, value_date, currency, reference_text),
            )
            self._connection.execute(
                """
                INSERT INTO posting (entry_ref, seq, account_ref, direction, amount_minor, currency)
                VALUES (%(entry)s, 1, %(debit)s, 'DEBIT', %(amount)s, %(currency)s),
                       (%(entry)s, 2, %(credit)s, 'CREDIT', %(amount)s, %(currency)s)
                """,
                {
                    "entry": reference,
                    "debit": debit,
                    "credit": credit,
                    "amount": amount_minor,
                    "currency": currency,
                },
            )
            self._connection.execute(
                "UPDATE balance SET booked_minor = booked_minor - %s WHERE account_ref = %s",
                (amount_minor, debit),
            )
            self._connection.execute(
                "UPDATE balance SET booked_minor = booked_minor + %s WHERE account_ref = %s",
                (amount_minor, credit),
            )
            return self._audit(
                action,
                reference,
                {
                    "debitAccountRef": debit,
                    "creditAccountRef": credit,
                    "amountMinor": str(amount_minor),
                    "currency": currency,
                    "valueDate": value_date,
                },
            )

    def _audit(self, action: str, subject: str, after: dict[str, str]) -> int:
        previous = self._connection.execute(
            "SELECT hash FROM audit_record ORDER BY seq DESC LIMIT 1"
        ).fetchone()
        previous_hash = previous[0] if previous else GENESIS_HASH
        payload = json.dumps({"action": action, "subject": subject, "after": after}, sort_keys=True)
        digest = hashlib.sha256(f"{previous_hash}{payload}".encode()).hexdigest()
        row = self._connection.execute(
            """
            INSERT INTO audit_record (occurred_at, actor, action, subject_ref, correlation_id,
                                      before_state, after_state, previous_hash, hash)
            VALUES (now(), 'ledger-api', %s, %s, NULL, '{}'::jsonb, %s::jsonb, %s, %s)
            RETURNING seq
            """,
            (action, subject, json.dumps(after, sort_keys=True), previous_hash, digest),
        ).fetchone()
        if row is None:  # pragma: no cover - RETURNING always yields a row
            raise RuntimeError("audit insert returned nothing")
        return int(row[0])
