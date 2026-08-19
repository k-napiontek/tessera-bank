"""Settings, read from the environment and validated before anything starts.

Two rules, the same two the Go gateway follows, because an estate whose components disagree about
how they read their configuration is an estate where every deployment is a separate puzzle:

A setting that is present but unparseable is an error, never a fall back to the default. An operator
who wrote ``TB_FRAUD_BLOCK_THRESHOLD=750.5`` meant something by it, and a service that quietly runs
on 750 has taken their intent and discarded it.

Every problem is reported at once. A loader that stops at the first is discovered during an
incident, one restart per variable.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, ClassVar, Final

LOG_LEVELS: Final = frozenset({"debug", "info", "warning", "error"})

# The contract's own limits. A score is an integer 0-1000 in
# contracts/asyncapi/ledger-events.yaml, so a threshold outside that range is not a threshold.
MIN_SCORE: Final = 0
MAX_SCORE: Final = 1000


class ConfigError(Exception):
    """Everything wrong with the environment, in one message."""

    def __init__(self, problems: list[str]) -> None:
        self.problems = sorted(problems)
        joined = "\n  - ".join(self.problems)
        super().__init__(f"the fraud-scoring configuration is not usable:\n  - {joined}")


@dataclass(frozen=True, slots=True)
class Settings:
    """The whole configuration. Nothing here changes after ``load`` returns."""

    brokers: str
    transfer_topic: str
    decision_topic: str
    group_id: str
    review_threshold: int
    block_threshold: int
    high_amount_minor: int
    reporting_threshold_minor: int
    metrics_port: int
    log_level: str
    poll_seconds: float

    #: Every default, in one place, because an operator's first question is what the value is when
    #: they set nothing. The topic names are the addresses the AsyncAPI document declares - a
    #: default that disagreed with the contract would consume nothing and look healthy doing it.
    DEFAULTS: ClassVar[Mapping[str, Any]] = MappingProxyType(
        {
            "TB_FRAUD_TRANSFER_TOPIC": "tessera.ledger.transfer-posted.v1",
            "TB_FRAUD_DECISION_TOPIC": "tessera.fraud.decision.v1",
            "TB_FRAUD_GROUP_ID": "fraud-scoring",
            "TB_FRAUD_REVIEW_THRESHOLD": 400,
            "TB_FRAUD_BLOCK_THRESHOLD": 750,
            # 100 000.00 in minor units. High relative to a retail transfer, not to a corporate one;
            # it is a threshold to tune per institution rather than a fact about fraud.
            "TB_FRAUD_HIGH_AMOUNT_MINOR": 10_000_000,
            # 10 000.00 in minor units - the EU cash reporting threshold that structuring
            # exists to stay under, which is why an amount just below it is worth a rule.
            "TB_FRAUD_REPORTING_THRESHOLD_MINOR": 1_000_000,
            "TB_FRAUD_METRICS_PORT": 9100,
            "TB_FRAUD_LOG_LEVEL": "info",
            "TB_FRAUD_POLL_SECONDS": 1.0,
        }
    )


def load(env: Mapping[str, str]) -> Settings:
    """Read and validate the configuration, or raise :class:`ConfigError` naming every problem."""
    reader = _Reader(env)

    settings = Settings(
        brokers=reader.required("TB_FRAUD_BROKERS"),
        transfer_topic=reader.text("TB_FRAUD_TRANSFER_TOPIC"),
        decision_topic=reader.text("TB_FRAUD_DECISION_TOPIC"),
        group_id=reader.text("TB_FRAUD_GROUP_ID"),
        review_threshold=reader.whole("TB_FRAUD_REVIEW_THRESHOLD", low=MIN_SCORE, high=MAX_SCORE),
        block_threshold=reader.whole("TB_FRAUD_BLOCK_THRESHOLD", low=MIN_SCORE, high=MAX_SCORE),
        high_amount_minor=reader.whole("TB_FRAUD_HIGH_AMOUNT_MINOR", low=1),
        reporting_threshold_minor=reader.whole("TB_FRAUD_REPORTING_THRESHOLD_MINOR", low=1),
        metrics_port=reader.whole("TB_FRAUD_METRICS_PORT", low=1, high=65535),
        log_level=reader.choice("TB_FRAUD_LOG_LEVEL", LOG_LEVELS),
        poll_seconds=reader.number("TB_FRAUD_POLL_SECONDS", low=0.0),
    )

    if settings.review_threshold >= settings.block_threshold:
        # With the two the same way round, REVIEW is unreachable: every score is either below both
        # or above both. A service that can only ever answer two of its three outcomes is one
        # nobody would notice was broken.
        reader.fail(
            "TB_FRAUD_REVIEW_THRESHOLD",
            f"must be below TB_FRAUD_BLOCK_THRESHOLD ({settings.block_threshold}), "
            f"or no transfer can ever be reviewed",
        )

    if reader.problems:
        raise ConfigError(reader.problems)
    return settings


class _Reader:
    """Accumulates problems instead of raising on the first one."""

    def __init__(self, env: Mapping[str, str]) -> None:
        self._env = env
        self.problems: list[str] = []

    def fail(self, name: str, message: str) -> None:
        self.problems.append(f"{name}: {message}")

    def _raw(self, name: str) -> str | None:
        """The supplied value, or None when the variable was not set at all.

        A variable set to the empty string counts as supplied: the operator wrote it, so it is
        validated rather than defaulted away.
        """
        value = self._env.get(name)
        return None if value is None else value.strip()

    def required(self, name: str) -> str:
        value = self._raw(name)
        if not value:
            self.fail(name, "is required and was not set")
            return ""
        return value

    def text(self, name: str) -> str:
        value = self._raw(name)
        if value is None:
            return str(Settings.DEFAULTS[name])
        if not value:
            self.fail(name, "was set to an empty value")
            return str(Settings.DEFAULTS[name])
        return value

    def choice(self, name: str, allowed: frozenset[str]) -> str:
        value = self.text(name)
        if value not in allowed:
            self.fail(name, f"is {value!r}, which is not one of {', '.join(sorted(allowed))}")
            return str(Settings.DEFAULTS[name])
        return value

    def whole(self, name: str, *, low: int, high: int | None = None) -> int:
        value = self._raw(name)
        fallback = int(Settings.DEFAULTS[name])
        if value is None:
            return fallback
        try:
            parsed = int(value)
        except ValueError:
            self.fail(name, f"is not a whole number: {value!r}")
            return fallback
        if parsed < low or (high is not None and parsed > high):
            bound = f"between {low} and {high}" if high is not None else f"at least {low}"
            self.fail(name, f"must be {bound}, got {parsed}")
            return fallback
        return parsed

    def number(self, name: str, *, low: float) -> float:
        value = self._raw(name)
        fallback = float(Settings.DEFAULTS[name])
        if value is None:
            return fallback
        try:
            parsed = float(value)
        except ValueError:
            self.fail(name, f"is not a number: {value!r}")
            return fallback
        if parsed <= low:
            self.fail(name, f"must be greater than {low}, got {parsed}")
            return fallback
        return parsed
