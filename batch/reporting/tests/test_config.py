"""The configuration refuses a bad run before it opens a connection.

A batch job discovers its configuration is wrong at 02:00, unattended, and the operator reads the
log the next morning. Reporting every problem at once is therefore not a nicety: a loader that stops
at the first one costs a night per variable.
"""

from __future__ import annotations

import pytest

from reporting.config import ConfigError, Settings, load_run, load_settings

ENV = {
    "TB_REPORT_DSN": "postgresql://ledger@localhost:5432/ledger",
}


def test_defaults_apply_when_only_the_dsn_is_set() -> None:
    settings = load_settings(ENV)

    assert settings.dsn == ENV["TB_REPORT_DSN"]
    assert settings.output_dir == Settings.DEFAULTS["TB_REPORT_OUTPUT_DIR"]
    assert settings.institution == Settings.DEFAULTS["TB_REPORT_INSTITUTION"]
    assert settings.log_level == "info"


def test_the_dsn_is_required() -> None:
    with pytest.raises(ConfigError) as raised:
        load_settings({})

    assert raised.value.problems == ["TB_REPORT_DSN: is required and was not set"]


def test_every_problem_is_reported_at_once() -> None:
    with pytest.raises(ConfigError) as raised:
        load_settings(
            {
                "TB_REPORT_DSN": "postgresql://ledger@localhost/ledger",
                "TB_REPORT_INSTITUTION": "TOOSHORT",
                "TB_REPORT_LOG_LEVEL": "verbose",
                "TB_REPORT_QUERY_TIMEOUT_SECONDS": "not-a-number",
            }
        )

    assert len(raised.value.problems) == 3
    assert any("TB_REPORT_INSTITUTION" in problem for problem in raised.value.problems)
    assert any("TB_REPORT_LOG_LEVEL" in problem for problem in raised.value.problems)
    assert any("TB_REPORT_QUERY_TIMEOUT_SECONDS" in problem for problem in raised.value.problems)


def test_a_present_but_unparseable_value_is_never_defaulted_away() -> None:
    # The operator wrote something. Running on the default takes their intent and discards it.
    with pytest.raises(ConfigError):
        load_settings({**ENV, "TB_REPORT_QUERY_TIMEOUT_SECONDS": "300s"})


def test_an_empty_value_is_a_problem_rather_than_an_absence() -> None:
    with pytest.raises(ConfigError):
        load_settings({**ENV, "TB_REPORT_INSTITUTION": ""})


def test_the_business_date_is_read_as_ccyymmdd() -> None:
    run = load_run(["--business-date", "20260818"])

    assert run.business_date.isoformat() == "2026-08-18"
    assert run.position is None


def test_a_position_reproduces_an_earlier_run() -> None:
    run = load_run(["--business-date", "20260818", "--position", "4711"])

    assert run.position == 4711


@pytest.mark.parametrize("value", ["2026-08-18", "18082026", "20261332", "today"])
def test_a_business_date_that_is_not_ccyymmdd_is_refused(value: str) -> None:
    with pytest.raises(ConfigError):
        load_run(["--business-date", value])


def test_a_negative_position_is_refused() -> None:
    # Audit sequence numbers start at 1. A run asked to reproduce position 0 would report an empty
    # ledger and look like a quiet day.
    with pytest.raises(ConfigError):
        load_run(["--business-date", "20260818", "--position", "0"])
