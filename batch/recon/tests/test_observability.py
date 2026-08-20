"""What a run says about itself, so a platform can act on breaks without parsing the report.

The metrics are the alerting hook the work package asks for. What matters here is that a break is
visible *by classification*: an alert that fires on "breaks > 0" would page somebody every morning
for timing differences that are expected, which is the same failure as classifying them as drift -
it trains the operator to ignore the signal.
"""

from __future__ import annotations

import pathlib

from recon.compare import Classification
from recon.observability import write_metrics
from recon.run import RunResult


def result(**overrides) -> RunResult:
    base = {
        "business_date_ccyymmdd": "20260818",
        "position_seq": 4711,
        "accounts_compared": 200,
        "accounts_matched": 198,
        "breaks_by_classification": {
            Classification.VALUE_DRIFT: 1,
            Classification.TIMING: 1,
            Classification.MISSING_ON_MASTER: 0,
            Classification.MISSING_IN_LEDGER: 0,
        },
        "total_absolute_drift_minor": 12_345,
        "duration_seconds": 1.5,
        "report_path": pathlib.Path("BREAKS-20260818.json"),
    }
    base.update(overrides)
    return RunResult(**base)


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def test_every_classification_gets_its_own_series(tmp_path: pathlib.Path) -> None:
    """Including zeroes: a series that vanishes when all is well is one nobody can chart."""
    path = tmp_path / "recon.prom"
    write_metrics(path, result())
    text = read(path)
    for classification in Classification:
        assert f'classification="{classification.value}"' in text


def test_timing_is_countable_apart_from_drift(tmp_path: pathlib.Path) -> None:
    path = tmp_path / "recon.prom"
    write_metrics(path, result())
    text = read(path)
    assert 'classification="TIMING"' in text
    assert 'classification="VALUE_DRIFT"' in text


def test_the_run_reports_its_own_success(tmp_path: pathlib.Path) -> None:
    """A job that stopped running and a job finding nothing look identical without this."""
    path = tmp_path / "recon.prom"
    write_metrics(path, result())
    assert "tessera_recon_last_success" in read(path)


def test_the_drift_total_is_exposed(tmp_path: pathlib.Path) -> None:
    path = tmp_path / "recon.prom"
    write_metrics(path, result(total_absolute_drift_minor=999))
    assert "999" in read(path)


def test_the_business_date_labels_the_volume_metrics(tmp_path: pathlib.Path) -> None:
    """So a rerun for a past date cannot be mistaken for this morning's figures."""
    path = tmp_path / "recon.prom"
    write_metrics(path, result())
    assert 'business_date="20260818"' in read(path)


def test_the_file_is_replaced_not_appended(tmp_path: pathlib.Path) -> None:
    path = tmp_path / "recon.prom"
    write_metrics(path, result(total_absolute_drift_minor=111))
    write_metrics(path, result(total_absolute_drift_minor=222))
    text = read(path)
    assert "222" in text
    assert "111" not in text
