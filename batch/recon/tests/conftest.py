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


# ------------------------------------------------------------------------------------------------
# The ledger side: real PostgreSQL with the ledger's own Flyway migrations, for the reason
# batch/reporting gives next door - a reader proved against a hand-written schema is verified
# against a fiction, and it keeps passing on the day the ledger adds a column.
#
# This scaffolding is close to reporting's and is deliberately not shared with it. Sharing would
# make batch/recon depend on batch/reporting, which would couple two jobs that must be able to
# disagree; F-66 records the duplication rather than hiding it.
# ------------------------------------------------------------------------------------------------

MIGRATIONS = (
    REPO / "services" / "ledger-persistence" / "src" / "main" / "resources" / "db" / "migration"
)

GENESIS_HASH = "0" * 64

#: Postings reach an account through an entry, and an entry is in scope when its audit row is.
ENTRY_ACTIONS = ["TRANSFER_POSTED", "TRANSFER_REVERSED"]


@pytest.fixture(scope="session")
def dsn() -> Iterator[str]:
    """A PostgreSQL with the ledger schema, shared by the suite."""
    import psycopg
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer("postgres:16-alpine") as container:
        url = container.get_connection_url().replace("postgresql+psycopg2://", "postgresql://")
        with psycopg.connect(url, autocommit=True) as connection:
            files = sorted(
                MIGRATIONS.glob("V*.sql"), key=lambda path: int(path.name.split("__")[0][1:])
            )
            if not files:
                raise RuntimeError(f"no ledger migrations found under {MIGRATIONS}")
            for path in files:
                connection.execute(path.read_text(encoding="utf-8"))
        yield url


@pytest.fixture
def ledger(dsn: str) -> Iterator[LedgerFixture]:
    """An empty ledger, truncated before and after each test."""
    import psycopg

    with psycopg.connect(dsn, autocommit=True) as connection:
        _empty(connection)
        yield LedgerFixture(connection)
        _empty(connection)


def _empty(connection) -> None:
    # posting and audit_record refuse DELETE and TRUNCATE by trigger - that is what makes them
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


class LedgerFixture:
    """Writes the rows the ledger's Java would write, so the reader has something true to read.

    It reproduces the ordering that matters and nothing else. The audit hashes are a plausible chain
    rather than the canonical encoding ``AuditEntry`` defines: recon depends on audit *order*, which
    the advisory lock guarantees, and not on the chain verifying. Reimplementing that encoding here
    would create a second definition of it, and the day the two disagreed this suite would be
    asserting against the copy.
    """

    def __init__(self, connection) -> None:
        self._connection = connection

    def open_account(
        self,
        reference: str,
        *,
        customer_ref: str = "CU0000000001",
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
    ) -> int:
        """Both legs, both balances and the audit row in one transaction - as Transfer does."""
        with self._connection.transaction():
            self._connection.execute(
                """
                INSERT INTO journal_entry (reference, value_date, currency, reference_text)
                VALUES (%s, %s, %s, NULL)
                """,
                (reference, value_date, currency),
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
            return self._audit(
                "TRANSFER_POSTED",
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
        import hashlib
        import json

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
