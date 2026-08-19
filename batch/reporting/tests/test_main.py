"""The entry point, and the exit codes a scheduler reads."""

from __future__ import annotations

import json

import pytest

from reporting.main import main


def test_a_bad_configuration_exits_two_and_writes_nothing(monkeypatch, capsys, tmp_path) -> None:
    monkeypatch.delenv("TB_REPORT_DSN", raising=False)

    assert main(["--business-date", "20260818"]) == 2

    captured = capsys.readouterr()
    assert "TB_REPORT_DSN" in captured.err
    assert list(tmp_path.iterdir()) == []


def test_a_bad_business_date_exits_two(monkeypatch) -> None:
    monkeypatch.setenv("TB_REPORT_DSN", "postgresql://ledger@localhost/ledger")

    assert main(["--business-date", "18-08-2026"]) == 2


def test_a_failing_run_exits_one(monkeypatch, tmp_path) -> None:
    # Nothing is listening on this port, so the connection fails. The run started, so it is a 1 -
    # worth one retry - rather than a 2, which would fail identically forever.
    monkeypatch.setenv("TB_REPORT_DSN", "postgresql://nobody@127.0.0.1:1/nothing")
    monkeypatch.setenv("TB_REPORT_OUTPUT_DIR", str(tmp_path))

    assert main(["--business-date", "20260818"]) == 1


@pytest.mark.usefixtures("ledger")
def test_a_successful_run_exits_zero_and_writes_the_metrics(
    monkeypatch, capsys, dsn, tmp_path
) -> None:
    monkeypatch.setenv("TB_REPORT_DSN", dsn)
    monkeypatch.setenv("TB_REPORT_OUTPUT_DIR", str(tmp_path))
    monkeypatch.setenv("TB_REPORT_METRICS_PATH", str(tmp_path / "reporting.prom"))

    assert main(["--business-date", "20260818"]) == 0

    assert (tmp_path / "position-20260818.csv").exists()
    assert (tmp_path / "manifest-20260818.json").exists()
    assert "tessera_reporting_position" in (tmp_path / "reporting.prom").read_text(encoding="utf-8")

    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines() if line.strip()]
    assert lines[-1]["msg"] == "reporting run complete"
    assert lines[-1]["business_date"] == "20260818"
