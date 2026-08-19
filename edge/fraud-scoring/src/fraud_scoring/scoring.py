"""The engine: rules in, a verdict out, and nothing else in between.

Three properties hold here, and each is a requirement rather than a preference.

**A rule is a pure function of one event and the parameters in force.** No clock, no randomness, no
lookup, no memory of what came before. That is what makes a replayed event score identically, which
REQ-FRD-003 requires - and it is why this service has no velocity rule. "Five transfers from this
account in ten minutes" is the most useful rule anyone could add here, and it depends on what this
consumer has already seen and in what order, so a replay would score differently and the
reproducibility claim would quietly become false.

**Every decision names the rules that produced it.** REQ-FRD-002: a score with no reason attached is
unusable where a customer can demand to know why their payment was flagged.

**The recorded version identifies the parameters as well as the code.** A rule catalogue version
alone is not enough to reproduce a decision, because the thresholds are configuration and a
deployment can change them without changing a line. The published ``modelVersion`` is therefore the
catalogue version plus a digest of the parameters that were in force, so a decision from months ago
can be checked against exactly the setup that produced it.
"""

from __future__ import annotations

import hashlib
from collections.abc import Callable
from dataclasses import dataclass, fields, replace

from fraud_scoring.events import (
    ALLOW,
    BLOCK,
    MAX_SCORE,
    MIN_SCORE,
    REVIEW,
    TransferPosted,
    Verdict,
)


@dataclass(frozen=True, slots=True)
class Parameters:
    """Everything a deployment can tune. Part of the recorded version, not just of the config."""

    high_amount_minor: int
    reporting_threshold_minor: int
    review_threshold: int
    block_threshold: int

    def fingerprint(self) -> str:
        """A short, stable digest of these values.

        Length-prefixed rather than concatenated: joining ``100`` and ``1000`` with a separator is
        fine until a value contains the separator, and a fingerprint that two different parameter
        sets can share is a fingerprint that certifies the wrong thing.
        """
        # dataclasses.fields rather than vars: a slotted dataclass has no __dict__, and the field
        # order is the declaration order rather than whatever a dict happens to hold.
        canonical = "".join(
            f"{len(field.name)}:{field.name}={len(str(getattr(self, field.name)))}:"
            f"{getattr(self, field.name)};"
            for field in sorted(fields(self), key=lambda f: f.name)
        )
        return hashlib.sha256(canonical.encode()).hexdigest()[:8]


@dataclass(frozen=True, slots=True)
class Rule:
    """One reason a transfer might be worth looking at."""

    code: str
    weight: int
    description: str
    fires: Callable[[TransferPosted, Parameters], bool]


@dataclass(frozen=True, slots=True)
class RuleSet:
    """A catalogue and the version it is published under."""

    version: str
    rules: tuple[Rule, ...]


class Engine:
    """Scores one event against one rule set."""

    def __init__(self, rule_set: RuleSet, parameters: Parameters) -> None:
        self._rule_set = rule_set
        self._parameters = parameters
        self._model_version = f"{rule_set.version}+{parameters.fingerprint()}"

    @property
    def model_version(self) -> str:
        """What the decision records, so it can be reproduced from it."""
        return self._model_version

    @property
    def parameters(self) -> Parameters:
        return self._parameters

    def score(self, event: TransferPosted) -> Verdict:
        """Apply every rule to the event and conclude."""
        fired = [rule for rule in self._rule_set.rules if rule.fires(event, self._parameters)]

        # Clamped, not wrapped and not left to run over: the contract fixes the range at 0-1000, and
        # a score outside it would be refused on the wire after the work had already been done.
        total = min(MAX_SCORE, max(MIN_SCORE, sum(rule.weight for rule in fired)))

        return Verdict(
            decision=self._decide(total),
            score=total,
            # Catalogue order, never set order or the order they happened to fire in. Two runs
            # listing the same reasons differently would make every comparison a judgement call.
            reason_codes=tuple(rule.code for rule in fired),
            model_version=self._model_version,
        )

    def _decide(self, score: int) -> str:
        if score >= self._parameters.block_threshold:
            return BLOCK
        if score >= self._parameters.review_threshold:
            return REVIEW
        return ALLOW

    def with_parameters(self, parameters: Parameters) -> Engine:
        """A second engine over the same catalogue - used by tests, and by nothing at runtime."""
        return Engine(self._rule_set, parameters)


def adjusted(parameters: Parameters, **changes: int) -> Parameters:
    """A copy with some values changed. Convenience for tests and for a canary deployment."""
    return replace(parameters, **changes)
