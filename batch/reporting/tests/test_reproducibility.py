"""Rerunning for a past date produces byte-identical output.

The Definition of Done asks for this in one line and it is the hardest thing in the package, because
the failure mode is invisible: a second run simply has more in it, every figure is internally
consistent, and nothing anywhere reports a discrepancy. The case that breaks a naive implementation
is a **backdated entry** - one posted today carrying yesterday's value date. A filter on value date
alone admits it on the rerun; the position does not, because the entry's audit row is above the
watermark.
"""

from __future__ import annotations

import datetime as dt
import json
import pathlib

import psycopg
import pytest

from reporting.config import RunRequest, Settings
from reporting.run import execute

BUSINESS_DATE = dt.date(2026, 8, 18)


def settings(dsn: str, output: pathlib.Path) -> Settings:
    return Settings(
        dsn=dsn,
        output_dir=str(output),
        institution="TESSPLPWXXX",
        metrics_path=str(output / "reporting.prom"),
        query_timeout_seconds=30,
        log_level="info",
    )


def seed(ledger) -> None:
    ledger.open_account("ACC-0000000001", account_type="LIABILITY")
    ledger.open_account("ACC-0000000002", account_type="ASSET")
    ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )


def files(directory: pathlib.Path) -> dict[str, bytes]:
    return {
        path.name: path.read_bytes()
        for path in sorted(directory.iterdir())
        if path.name.startswith(("position-", "movements-", "regulatory-extract-"))
    }


def test_a_rerun_at_the_recorded_position_is_byte_identical(dsn, ledger, tmp_path) -> None:
    seed(ledger)

    first_dir = tmp_path / "first"
    first = execute(settings(dsn, first_dir), RunRequest(BUSINESS_DATE, position=None))

    # Everything that could move the answer: a further transfer on the same business date, and a
    # backdated one carrying an earlier value date. Both are above the watermark, so neither is in
    # scope for the position the first run recorded.
    ledger.post_transfer(
        "TB202608180000000002",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=9_900,
        value_date="2026-08-18",
    )
    ledger.post_transfer(
        "TB202608150000000001",
        debit="ACC-0000000002",
        credit="ACC-0000000001",
        amount_minor=4_200,
        value_date="2026-08-15",
    )

    second_dir = tmp_path / "second"
    second = execute(
        settings(dsn, second_dir), RunRequest(BUSINESS_DATE, position=first.position.seq)
    )

    assert files(first_dir) == files(second_dir)
    assert second.position == first.position


def test_a_fresh_cut_after_new_activity_is_not_identical(dsn, ledger, tmp_path) -> None:
    # The counterpart, and the reason the test above proves anything: without a position the second
    # run genuinely does differ, so byte-identity is a property of the position rather than of a
    # ledger that happened not to change.
    seed(ledger)

    first_dir = tmp_path / "first"
    execute(settings(dsn, first_dir), RunRequest(BUSINESS_DATE, position=None))

    ledger.post_transfer(
        "TB202608180000000002",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=9_900,
        value_date="2026-08-18",
    )

    second_dir = tmp_path / "second"
    execute(settings(dsn, second_dir), RunRequest(BUSINESS_DATE, position=None))

    assert files(first_dir) != files(second_dir)


def test_the_backdated_entry_is_the_case_that_matters(dsn, ledger, tmp_path) -> None:
    # Isolated so the reason is unmistakable. Only a backdated entry is both outside the position
    # and inside a value-date filter, so it is the single case that separates a correct
    # implementation from one that filters on value date alone.
    seed(ledger)

    first_dir = tmp_path / "first"
    first = execute(settings(dsn, first_dir), RunRequest(BUSINESS_DATE, position=None))

    ledger.post_transfer(
        "TB202608150000000001",
        debit="ACC-0000000002",
        credit="ACC-0000000001",
        amount_minor=4_200,
        value_date="2026-08-15",
    )

    second_dir = tmp_path / "second"
    execute(settings(dsn, second_dir), RunRequest(BUSINESS_DATE, position=first.position.seq))

    assert files(first_dir) == files(second_dir)


def test_the_manifest_records_the_position_and_a_digest_of_every_file(
    dsn, ledger, tmp_path
) -> None:
    seed(ledger)
    output = tmp_path / "out"
    result = execute(settings(dsn, output), RunRequest(BUSINESS_DATE, position=None))

    manifest = json.loads((output / "manifest-20260818.json").read_text(encoding="utf-8"))

    assert manifest["businessDate"] == "20260818"
    assert manifest["position"]["seq"] == result.position.seq
    assert manifest["position"]["chainHash"] == result.position.chain_hash
    assert {entry["name"] for entry in manifest["files"]} == set(files(output))
    for entry in manifest["files"]:
        assert len(entry["sha256"]) == 64
        assert entry["bytes"] == (output / entry["name"]).stat().st_size


def test_the_manifest_is_the_only_artefact_carrying_a_clock(dsn, ledger, tmp_path) -> None:
    seed(ledger)
    output = tmp_path / "out"
    execute(settings(dsn, output), RunRequest(BUSINESS_DATE, position=None))

    manifest = json.loads((output / "manifest-20260818.json").read_text(encoding="utf-8"))

    # An aware instant. A naive one cannot be placed in time, which is the whole reason DTZ is on.
    assert manifest["generatedAt"].endswith("+00:00")
    for name, content in files(output).items():
        assert b"generatedAt" not in content, f"{name} carries a clock"


def test_reproducing_a_position_this_ledger_does_not_hold_fails(dsn, ledger, tmp_path) -> None:
    seed(ledger)

    with pytest.raises(LookupError):
        execute(settings(dsn, tmp_path / "out"), RunRequest(BUSINESS_DATE, position=9_999))


def test_the_run_reports_what_it_read_and_wrote(dsn, ledger, tmp_path) -> None:
    seed(ledger)
    result = execute(settings(dsn, tmp_path / "out"), RunRequest(BUSINESS_DATE, position=None))

    assert result.accounts_read == 2
    assert result.movements_read == 2
    assert result.records_written["regulatory-extract-20260818.txt"] == 4  # header, two, trailer


def test_the_output_directory_is_created_when_it_does_not_exist(dsn, ledger, tmp_path) -> None:
    seed(ledger)
    output = tmp_path / "deep" / "nested"

    execute(settings(dsn, output), RunRequest(BUSINESS_DATE, position=None))

    assert (output / "position-20260818.csv").exists()


def test_the_reader_connection_is_closed_even_when_a_report_fails(dsn, ledger, tmp_path) -> None:
    # An unbalanced position raises out of the report. A batch job that leaked its connection on the
    # error path would exhaust the ledger's pool overnight, which is a worse outage than the report
    # nobody got.
    ledger.open_account("ACC-0000000001")
    before = _connection_count(dsn)

    with pytest.raises(Exception, match=r"does not balance|no account"):
        _execute_unbalanced(dsn, tmp_path)

    assert _connection_count(dsn) <= before


def _execute_unbalanced(dsn: str, tmp_path: pathlib.Path) -> None:
    with psycopg.connect(dsn, autocommit=True) as connection:
        connection.execute(
            """
            INSERT INTO journal_entry (reference, value_date, currency)
            VALUES ('TB202608180000000009', '2026-08-18', 'PLN')
            """
        )
        connection.execute("SET session_replication_role = replica")
        connection.execute(
            """
            INSERT INTO posting (entry_ref, seq, account_ref, direction, amount_minor, currency)
            VALUES ('TB202608180000000009', 1, 'ACC-0000000001', 'DEBIT', 500, 'PLN')
            """
        )
        connection.execute("SET session_replication_role = origin")
        connection.execute(
            """
            INSERT INTO audit_record (occurred_at, actor, action, subject_ref, before_state,
                                      after_state, previous_hash, hash)
            VALUES (now(), 'test', 'TRANSFER_POSTED', 'TB202608180000000009', '{}'::jsonb,
                    '{}'::jsonb, %s, %s)
            """,
            ("1" * 64, "2" * 64),
        )
    execute(settings(dsn, tmp_path / "out"), RunRequest(BUSINESS_DATE, position=None))


def _connection_count(dsn: str) -> int:
    with psycopg.connect(dsn) as connection:
        row = connection.execute(
            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
        ).fetchone()
        return int(row[0])
