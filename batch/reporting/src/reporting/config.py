"""Settings and run parameters, validated before anything connects.

Split in two on purpose, because a batch job has two kinds of configuration and conflating them is
how a rerun ends up reporting on the wrong day:

* **Settings** come from the environment and describe the *installation* - where the ledger is,
  where output goes, who is reporting. They are the same on every run.
* **A RunRequest** comes from the command line and describes *this* run - which business date, and
  which ledger position when an earlier run is being reproduced. It differs every time.

Two rules govern both, the same two the gateway and fraud-scoring follow, because an estate whose
components disagree about how they read their configuration is an estate where every deployment is a
separate puzzle. A setting that is present but unparseable is an error, never a fall back to the
default. And every problem is reported at once: this job runs unattended at 02:00, so a loader that
stops at the first problem costs one night per variable.
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, ClassVar, Final

__all__ = [
    "LOG_LEVELS",
    "ConfigError",
    "RunRequest",
    "Settings",
    "load_run",
    "load_settings",
]

LOG_LEVELS: Final = frozenset({"debug", "info", "warning", "error"})

#: CCYYMMDD, the form the JCL runner at stratum 0 already takes. One estate, one way of writing a
#: business date, even across thirty years of technology.
BUSINESS_DATE = re.compile(r"^\d{8}$")

#: A BIC is 11 characters: four for the institution, two for the country, two for the location and
#: three for the branch. Checked for shape only - this repository is not a BIC registry.
BIC = re.compile(r"^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}[A-Z0-9]{3}$")


class ConfigError(Exception):
    """Everything wrong with the configuration, in one message."""

    def __init__(self, problems: list[str]) -> None:
        self.problems = sorted(problems)
        joined = "\n  - ".join(self.problems)
        super().__init__(f"the reporting configuration is not usable:\n  - {joined}")


@dataclass(frozen=True, slots=True)
class Settings:
    """Where the ledger is and where output goes. Fixed once ``load_settings`` returns."""

    dsn: str
    output_dir: str
    institution: str
    metrics_path: str
    query_timeout_seconds: int
    log_level: str

    #: Every default in one place, because an operator's first question is what the value is when
    #: they set nothing.
    DEFAULTS: ClassVar[Mapping[str, Any]] = MappingProxyType(
        {
            "TB_REPORT_OUTPUT_DIR": "out",
            # Synthetic, and shaped like a real BIC: TESS in Poland, Warsaw, head office. Every
            # identifier in this repository is invented; see the data classification document.
            "TB_REPORT_INSTITUTION": "TESSPLPWXXX",
            "TB_REPORT_METRICS_PATH": "out/reporting.prom",
            # Five minutes. A reporting query that has not returned by then is scanning something it
            # should be seeking, and a batch job that hangs until morning is worse than one that
            # fails at 02:05 with a statement timeout naming the query.
            "TB_REPORT_QUERY_TIMEOUT_SECONDS": 300,
            "TB_REPORT_LOG_LEVEL": "info",
        }
    )


@dataclass(frozen=True, slots=True)
class RunRequest:
    """What this run reports on."""

    business_date: dt.date

    #: The audit high-water mark to report as at. ``None`` means cut a fresh one, which is what a
    #: scheduled run does; a value means reproduce the run that recorded it.
    position: int | None


def load_settings(env: Mapping[str, str]) -> Settings:
    """Read and validate the environment, or raise :class:`ConfigError` naming every problem."""
    reader = _Reader()

    settings = Settings(
        dsn=reader.required(env, "TB_REPORT_DSN"),
        output_dir=reader.text(env, "TB_REPORT_OUTPUT_DIR"),
        institution=reader.pattern(env, "TB_REPORT_INSTITUTION", BIC, "an 11-character BIC"),
        metrics_path=reader.text(env, "TB_REPORT_METRICS_PATH"),
        query_timeout_seconds=reader.whole(env, "TB_REPORT_QUERY_TIMEOUT_SECONDS", low=1),
        log_level=reader.choice(env, "TB_REPORT_LOG_LEVEL", LOG_LEVELS),
    )

    if reader.problems:
        raise ConfigError(reader.problems)
    return settings


def load_run(argv: Sequence[str]) -> RunRequest:
    """Read and validate the command line, or raise :class:`ConfigError`."""
    parser = argparse.ArgumentParser(
        prog="reporting",
        description="Generate the daily reports and the regulatory extract from the ledger.",
    )
    parser.add_argument(
        "--business-date",
        required=True,
        metavar="CCYYMMDD",
        help="the date to report on, as CCYYMMDD",
    )
    parser.add_argument(
        "--position",
        metavar="SEQ",
        help="reproduce the run that recorded this ledger position; omit to cut a fresh one",
    )
    # argparse exits the process on a bad argument, which is right for a CLI and wrong for a loader
    # a test can exercise. Its own errors are converted rather than caught late.
    try:
        parsed = parser.parse_args(list(argv))
    except SystemExit as exit_:  # pragma: no cover - argparse writes its own message first
        raise ConfigError(["the command line could not be parsed"]) from exit_

    problems: list[str] = []

    business_date: dt.date | None = None
    if not BUSINESS_DATE.match(parsed.business_date):
        problems.append(f"--business-date: is not CCYYMMDD: {parsed.business_date!r}")
    else:
        text = parsed.business_date
        try:
            # Built from its parts rather than parsed by strptime, which would produce a naive
            # datetime and then throw the time away. A business date is a date: it has no time and
            # no zone, and giving it either invites a run to report on the wrong day either side of
            # midnight.
            business_date = dt.date(int(text[0:4]), int(text[4:6]), int(text[6:8]))
        except ValueError:
            problems.append(f"--business-date: is not a real date: {text!r}")

    position: int | None = None
    if parsed.position is not None:
        try:
            position = int(parsed.position)
        except ValueError:
            problems.append(f"--position: is not a whole number: {parsed.position!r}")
        else:
            if position < 1:
                # Audit sequence numbers are an identity column starting at 1. A run asked to
                # reproduce position 0 would report an empty ledger and look like a quiet day.
                problems.append(f"--position: must be at least 1, got {position}")

    if problems:
        raise ConfigError(problems)

    # business_date is set here by construction: the only paths that leave it None append a problem,
    # and the raise above has already taken them.
    return RunRequest(business_date=business_date, position=position)


class _Reader:
    """Accumulates problems instead of raising on the first one."""

    def __init__(self) -> None:
        self.problems: list[str] = []

    def fail(self, name: str, message: str) -> None:
        self.problems.append(f"{name}: {message}")

    def _raw(self, env: Mapping[str, str], name: str) -> str | None:
        """The supplied value, or None when the variable was not set at all.

        A variable set to the empty string counts as supplied: the operator wrote it, so it is
        validated rather than defaulted away.
        """
        value = env.get(name)
        return None if value is None else value.strip()

    def required(self, env: Mapping[str, str], name: str) -> str:
        value = self._raw(env, name)
        if not value:
            self.fail(name, "is required and was not set")
            return ""
        return value

    def text(self, env: Mapping[str, str], name: str) -> str:
        value = self._raw(env, name)
        if value is None:
            return str(Settings.DEFAULTS[name])
        if not value:
            self.fail(name, "was set to an empty value")
            return str(Settings.DEFAULTS[name])
        return value

    def choice(self, env: Mapping[str, str], name: str, allowed: frozenset[str]) -> str:
        value = self.text(env, name)
        if value not in allowed:
            self.fail(name, f"is {value!r}, which is not one of {', '.join(sorted(allowed))}")
            return str(Settings.DEFAULTS[name])
        return value

    def pattern(
        self, env: Mapping[str, str], name: str, pattern: re.Pattern[str], description: str
    ) -> str:
        value = self.text(env, name)
        if not pattern.match(value):
            self.fail(name, f"is {value!r}, which is not {description}")
            return str(Settings.DEFAULTS[name])
        return value

    def whole(self, env: Mapping[str, str], name: str, *, low: int) -> int:
        value = self._raw(env, name)
        fallback = int(Settings.DEFAULTS[name])
        if value is None:
            return fallback
        try:
            parsed = int(value)
        except ValueError:
            self.fail(name, f"is not a whole number: {value!r}")
            return fallback
        if parsed < low:
            self.fail(name, f"must be at least {low}, got {parsed}")
            return fallback
        return parsed
