"""The configuration refuses to start on anything it does not understand."""

import pytest

from fraud_scoring.config import ConfigError, Settings, load


def complete() -> dict[str, str]:
    """The smallest environment that boots: the one setting with no sensible default."""
    return {"TB_FRAUD_BROKERS": "kafka-1.internal:9092,kafka-2.internal:9092"}


def test_defaults_are_the_contract_topics() -> None:
    settings = load(complete())

    # The topic names are the addresses in contracts/asyncapi/ledger-events.yaml. A default that
    # disagreed with the contract would produce a service that consumes nothing and looks healthy.
    assert settings.transfer_topic == "tessera.ledger.transfer-posted.v1"
    assert settings.decision_topic == "tessera.fraud.decision.v1"
    assert settings.group_id == "fraud-scoring"
    assert settings.review_threshold == 400
    assert settings.block_threshold == 750


def test_brokers_are_required() -> None:
    with pytest.raises(ConfigError) as raised:
        load({})

    assert "TB_FRAUD_BROKERS" in str(raised.value)


def test_a_present_but_empty_setting_is_refused() -> None:
    env = complete()
    env["TB_FRAUD_TRANSFER_TOPIC"] = ""

    with pytest.raises(ConfigError):
        load(env)


def test_every_problem_is_reported_at_once() -> None:
    with pytest.raises(ConfigError) as raised:
        load(
            {
                "TB_FRAUD_REVIEW_THRESHOLD": "not a number",
                "TB_FRAUD_HIGH_AMOUNT_MINOR": "-1",
            }
        )

    # One restart per variable is how a first-error-wins loader is discovered, during an incident.
    message = str(raised.value)
    for name in (
        "TB_FRAUD_BROKERS",
        "TB_FRAUD_REVIEW_THRESHOLD",
        "TB_FRAUD_HIGH_AMOUNT_MINOR",
    ):
        assert name in message


def test_an_unparseable_number_fails_rather_than_defaulting() -> None:
    env = complete()
    env["TB_FRAUD_BLOCK_THRESHOLD"] = "750.5"

    with pytest.raises(ConfigError) as raised:
        load(env)

    assert "TB_FRAUD_BLOCK_THRESHOLD" in str(raised.value)


@pytest.mark.parametrize(
    ("review", "block"),
    [
        ("750", "400"),  # inverted
        ("400", "400"),  # equal: nothing could ever be reviewed
        ("-1", "750"),  # outside the contract's range
        ("400", "1001"),
    ],
)
def test_thresholds_must_leave_all_three_outcomes_reachable(review: str, block: str) -> None:
    env = complete()
    env["TB_FRAUD_REVIEW_THRESHOLD"] = review
    env["TB_FRAUD_BLOCK_THRESHOLD"] = block

    # A block threshold at or below the review threshold makes REVIEW unreachable, and a service
    # that can only ever answer two of its three outcomes is one nobody would notice was broken.
    with pytest.raises(ConfigError):
        load(env)


def test_amount_thresholds_must_be_positive() -> None:
    for name in ("TB_FRAUD_HIGH_AMOUNT_MINOR", "TB_FRAUD_REPORTING_THRESHOLD_MINOR"):
        env = complete()
        env[name] = "0"

        with pytest.raises(ConfigError):
            load(env)


def test_an_unknown_log_level_is_refused() -> None:
    env = complete()
    env["TB_FRAUD_LOG_LEVEL"] = "verbose"

    with pytest.raises(ConfigError):
        load(env)


def test_every_setting_can_be_overridden() -> None:
    settings = load(
        {
            "TB_FRAUD_BROKERS": "localhost:9092",
            "TB_FRAUD_TRANSFER_TOPIC": "tessera.ledger.transfer-posted.v2",
            "TB_FRAUD_DECISION_TOPIC": "tessera.fraud.decision.v2",
            "TB_FRAUD_GROUP_ID": "fraud-scoring-canary",
            "TB_FRAUD_REVIEW_THRESHOLD": "300",
            "TB_FRAUD_BLOCK_THRESHOLD": "900",
            "TB_FRAUD_HIGH_AMOUNT_MINOR": "5000000",
            "TB_FRAUD_REPORTING_THRESHOLD_MINOR": "1000000",
            "TB_FRAUD_METRICS_PORT": "9110",
            "TB_FRAUD_LOG_LEVEL": "debug",
            "TB_FRAUD_POLL_SECONDS": "0.5",
        }
    )

    assert settings.brokers == "localhost:9092"
    assert settings.group_id == "fraud-scoring-canary"
    assert settings.review_threshold == 300
    assert settings.block_threshold == 900
    assert settings.high_amount_minor == 5_000_000
    assert settings.reporting_threshold_minor == 1_000_000
    assert settings.metrics_port == 9110
    assert settings.log_level == "debug"
    assert settings.poll_seconds == 0.5


def test_settings_are_frozen() -> None:
    settings = load(complete())

    # Configuration read once at boot and then mutated at runtime is configuration nobody can
    # reason about from the log line that reported it.
    with pytest.raises(AttributeError):
        settings.review_threshold = 1  # type: ignore[misc]


def test_settings_never_print_a_secret() -> None:
    settings = load(complete())

    # There is no credential here today - the brokers are addresses - but repr goes into log lines
    # and crash reports, so the shape is asserted now rather than after a SASL password is added.
    assert isinstance(repr(settings), str)
    assert "password" not in repr(settings).lower()


def test_the_defaults_are_stated_in_one_place() -> None:
    # An operator's first question is what the value is when they set nothing. The answer has to be
    # readable without tracing every call.
    assert Settings.DEFAULTS["TB_FRAUD_REVIEW_THRESHOLD"] == 400
    assert Settings.DEFAULTS["TB_FRAUD_BLOCK_THRESHOLD"] == 750
