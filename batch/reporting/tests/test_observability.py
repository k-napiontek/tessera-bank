"""What a batch run says about itself.

A scheduled job has no endpoint to scrape: by the time anything asked, the process is gone. So the
metrics are written to a file in the node_exporter textfile format, which is how batch metrics
actually reach Prometheus. The alternative - a pushgateway - keeps the last value forever and turns
a job that stopped running into a job whose figures look healthy.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib

from reporting.ledger import Position
from reporting.observability import JsonFormatter, configure_logging, write_metrics
from reporting.run import RunResult

RESULT = RunResult(
    business_date_ccyymmdd="20260818",
    position=Position(seq=4711, chain_hash="9f" * 32),
    accounts_read=2,
    movements_read=4,
    records_written={"position-20260818.csv": 4, "regulatory-extract-20260818.txt": 4},
    duration_seconds=1.25,
    output_dir=pathlib.Path("out"),
)


def test_the_metrics_file_is_the_textfile_exposition_format(tmp_path) -> None:
    path = tmp_path / "reporting.prom"
    write_metrics(path, RESULT)

    body = path.read_text(encoding="utf-8")

    assert "# HELP tessera_reporting_duration_seconds" in body
    assert "# TYPE tessera_reporting_duration_seconds gauge" in body
    assert "tessera_reporting_duration_seconds 1.25" in body


def test_every_metric_carries_the_business_date_it_belongs_to(tmp_path) -> None:
    # Without the label, a rerun for a past date silently overwrites today's figures and an operator
    # reads yesterday's run as this morning's.
    path = tmp_path / "reporting.prom"
    write_metrics(path, RESULT)

    body = path.read_text(encoding="utf-8")

    assert 'tessera_reporting_accounts_read{business_date="20260818"} 2.0' in body
    assert 'tessera_reporting_movements_read{business_date="20260818"} 4.0' in body


def test_records_written_is_reported_per_file(tmp_path) -> None:
    path = tmp_path / "reporting.prom"
    write_metrics(path, RESULT)

    body = path.read_text(encoding="utf-8")

    assert 'report="position-20260818.csv"' in body
    assert 'report="regulatory-extract-20260818.txt"' in body


def test_the_position_is_exported_so_a_stalled_ledger_is_visible(tmp_path) -> None:
    # A position that does not advance between nightly runs means nothing posted, which is either a
    # very quiet day or an upstream that stopped. Only the metric can tell an operator to ask.
    path = tmp_path / "reporting.prom"
    write_metrics(path, RESULT)

    assert "tessera_reporting_position 4711.0" in path.read_text(encoding="utf-8")


def test_the_metrics_directory_is_created_if_it_is_missing(tmp_path) -> None:
    path = tmp_path / "nested" / "reporting.prom"
    write_metrics(path, RESULT)

    assert path.exists()


def test_a_log_line_is_one_json_object_carrying_what_the_call_site_attached() -> None:
    record = logging.LogRecord("reporting", logging.INFO, __file__, 1, "run complete", None, None)
    record.business_date = "20260818"
    record.position = 4711

    payload = json.loads(JsonFormatter().format(record))

    assert payload["msg"] == "run complete"
    assert payload["level"] == "INFO"
    assert payload["business_date"] == "20260818"
    assert payload["position"] == 4711


def test_the_remittance_reference_never_reaches_a_log_line() -> None:
    # journal_entry.reference_text is the one piece of free text a paying customer controls, and it
    # is where a name or a note about a person ends up. The ledger keeps it out of its audit rows;
    # a report's log is read by more people than the ledger's audit trail is.
    record = logging.LogRecord("reporting", logging.INFO, __file__, 1, "posted", None, None)
    record.reference = "SALARY FOR SOMEBODY"
    record.account_ref = "ACC-0000000001"

    payload = json.loads(JsonFormatter().format(record))

    assert "reference" not in payload
    assert payload["account_ref"] == "ACC-0000000001"


def test_configure_logging_replaces_whatever_was_there(capsys) -> None:
    configure_logging("info")
    logging.getLogger("reporting").info("started", extra={"business_date": "20260818"})

    line = capsys.readouterr().out.strip()

    assert json.loads(line)["business_date"] == "20260818"
    assert dt.datetime.now(tz=dt.UTC).strftime("%Y") in json.loads(line)["time"]
