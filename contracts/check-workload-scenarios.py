#!/usr/bin/env python3
"""Validate the committed failure-scenario catalogue, and then check the things a schema cannot say.

Three jobs.

The first is ordinary JSON Schema validation of contracts/workload/tessera-scenarios-v1.json against
contracts/workload/scenario.schema.json. The validator is **imported from check-workload-model.py
rather than copied**, for the reason check-slo-catalogue.py next door gives: this repository asserts
one hand-written subset of JSON Schema 2020-12, and a second copy of it would be the duplication
F-61, F-64 and F-66 each record rotting - in a place where the two copies disagreeing would be
silent. Importing it also inherits the property that makes it worth having: an unrecognised keyword
is a **failure rather than a silent pass**, because a validator that ignores what it does not know
agrees with a schema it never checked. F-20 records what a suite printing thirteen lines of PASS over
a check it skipped costs.

The two schema-shaped controls come from the same place. The personal-data denylist is
check-workload-model.py's - every object closed, every string bounded, no property a name could go in
- and the alerting denylist is check-slo-catalogue.py's, because a scenario naming who to notify
would be ADR 0012's boundary crossed a second time.

The second is the cross-reference. Every objective identifier a scenario names is resolved against
contracts/slo/tessera-slo-v1.json, so a scenario cannot expect an objective the estate does not have,
and an identifier renamed in the catalogue and left stale in a scenario fails the build. That is the
direction ADR 0012 calls the more dangerous one, "because it looks like a control".

The third is what makes a signature falsifiable rather than descriptive. A scenario's move list and
flat list must be **disjoint**: an objective expected both to move and to stay flat is a prediction
no observation can contradict. A scenario that moves nothing must say why, in the same shape as
noObjectiveBecause on an SLO signal, because an estate that cannot see a condition is a finding and
an empty list on its own is indistinguishable from an unfinished one. And the per-condition parameter
rules live here rather than in the schema, since expressing them there would need oneOf, which the
shared subset deliberately does not assert.

It does **not** check that the injector can produce any of these conditions. That is workload/'s own
test, and the two claims are checked from opposite sides on purpose - the same division as
check-workload-model.py, which validates the day model without proving that the engine's schedule
follows it.

Standard library only, so it runs from a clean checkout with nothing installed.
"""

import importlib.util
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = REPO / "contracts" / "workload" / "scenario.schema.json"
CATALOGUE = REPO / "contracts" / "workload" / "tessera-scenarios-v1.json"
SLO_CATALOGUE = REPO / "contracts" / "slo" / "tessera-slo-v1.json"
VALIDATOR = REPO / "contracts" / "check-workload-model.py"
ALERTING = REPO / "contracts" / "check-slo-catalogue.py"

# --------------------------------------------------------------------------------------------
# Transcribed, not read out of the document being checked. A checker whose expectations come
# entirely from its own input agrees with every version of that input, including a wrong one. The
# same reasoning, and the same comment, as check-workload-model.py and check-slo-catalogue.py.
# --------------------------------------------------------------------------------------------
CATALOGUE_ID = "TB-SCENARIOS-V1"

#: The conditions WP-24a's In scope section names, in its own order. The catalogue must hold every
#: one of them: a condition dropped during execution because it turned out awkward is exactly what
#: the work package's Constraint says must be recorded as a finding instead.
REQUIRED_CONDITIONS = (
    "slow-dependency",
    "partial-outage",
    "stuck-outbox",
    "consumer-lag",
    "rate-limit-storm",
    "pool-exhaustion",
    "clock-skew",
)

#: What each condition needs from `parameters`, and what it may not carry. A parameter that belongs
#: to another condition is not harmless: the injector dispatches on the condition and would ignore
#: it, so the scenario would read as though a dial had been set that nothing ever looked at.
PARAMETER_RULES = {
    # condition: (required, permitted)
    "slow-dependency": ({"extraLatencyMillis"}, {"extraLatencyMillis"}),
    "partial-outage": (set(), set()),
    "stuck-outbox": (set(), set()),
    "consumer-lag": (set(), set()),
    "rate-limit-storm": ({"requestsPerSecond", "subjects"}, {"requestsPerSecond", "subjects"}),
    "pool-exhaustion": ({"lockMode", "lockTable"}, {"lockMode", "lockTable"}),
    "clock-skew": ({"skewMinutes"}, {"skewMinutes"}),
}

#: A target that is not a component of the bank. The SLO catalogue knows nothing about these two
#: because neither emits a metric this estate owns - they are what the fixture boots underneath it.
INFRASTRUCTURE_TARGETS = frozenset({"postgresql", "kafka"})


# --------------------------------------------------------------------------------------------
# What a schema cannot say
# --------------------------------------------------------------------------------------------


def declared_objectives() -> set[str]:
    """Every objective identifier the SLO catalogue declares."""
    catalogue = json.loads(SLO_CATALOGUE.read_text("utf-8"))
    return {
        objective["objectiveId"]
        for component in catalogue["components"]
        for objective in component["objectives"]
    }


def declared_components() -> set[str]:
    """Every component identifier the SLO catalogue declares."""
    catalogue = json.loads(SLO_CATALOGUE.read_text("utf-8"))
    return {component["componentId"] for component in catalogue["components"]}


def check_identifiers_are_unique(catalogue: dict) -> list[str]:
    problems: list[str] = []
    seen: set[str] = set()
    for scenario in catalogue["scenarios"]:
        identifier = scenario["scenarioId"]
        if identifier in seen:
            problems.append(
                f"{identifier}: declared twice. A committed signature capture is filed under the "
                f"identifier, so two scenarios sharing one produce two measurements in one place."
            )
        seen.add(identifier)
    return problems


def check_every_condition_is_covered(catalogue: dict) -> list[str]:
    present = {scenario["condition"] for scenario in catalogue["scenarios"]}
    missing = [condition for condition in REQUIRED_CONDITIONS if condition not in present]
    if not missing:
        return []
    return [
        f"the catalogue declares no scenario for {', '.join(missing)}. WP-24a's In scope names all "
        f"{len(REQUIRED_CONDITIONS)}; a condition that cannot be produced is recorded as a finding "
        f"about the component's testability, not dropped from the catalogue."
    ]


def check_objectives_resolve(catalogue: dict) -> list[str]:
    """Every objective a scenario names must be one the estate actually declares."""
    known = declared_objectives()
    problems: list[str] = []
    for scenario in catalogue["scenarios"]:
        at = scenario["scenarioId"]
        for field in ("movesObjectives", "flatObjectives"):
            for identifier in scenario[field]:
                if identifier not in known:
                    problems.append(
                        f"{at}/{field}: {identifier} is in no component of "
                        f"{SLO_CATALOGUE.relative_to(REPO)}. Either the objective was renamed and "
                        f"this reference is stale, or the scenario expects one the estate has never "
                        f"declared."
                    )
    return problems


def check_the_two_lists_are_disjoint(catalogue: dict) -> list[str]:
    problems: list[str] = []
    for scenario in catalogue["scenarios"]:
        both = sorted(set(scenario["movesObjectives"]) & set(scenario["flatObjectives"]))
        if both:
            problems.append(
                f"{scenario['scenarioId']}: {', '.join(both)} is expected both to move and to stay "
                f"flat, which no observation can contradict. A signature that cannot be wrong is not "
                f"a signature."
            )
        for field in ("movesObjectives", "flatObjectives"):
            names = scenario[field]
            if len(names) != len(set(names)):
                problems.append(f"{scenario['scenarioId']}/{field}: names an objective twice")
    return problems


def check_a_scenario_that_moves_nothing_says_why(catalogue: dict) -> list[str]:
    """An empty move list is a finding about the estate, and a finding has to be written down."""
    problems: list[str] = []
    for scenario in catalogue["scenarios"]:
        at = scenario["scenarioId"]
        moves = scenario["movesObjectives"]
        reason = scenario.get("movesNothingBecause")
        if not moves and reason is None:
            problems.append(
                f"{at}: moves no objective and does not say why. An estate that cannot see a "
                f"condition is a finding; an empty list on its own is indistinguishable from an "
                f"unfinished entry."
            )
        if moves and reason is not None:
            problems.append(
                f"{at}: carries movesNothingBecause while expecting "
                f"{len(moves)} objective(s) to move. One of the two is wrong."
            )
        if not moves and not scenario["flatObjectives"]:
            problems.append(
                f"{at}: expects nothing to move and names nothing that must stay flat, so the run "
                f"asserts nothing at all."
            )
    return problems


def check_parameters(catalogue: dict) -> list[str]:
    problems: list[str] = []
    for scenario in catalogue["scenarios"]:
        at = scenario["scenarioId"]
        condition = scenario["condition"]
        if condition not in PARAMETER_RULES:
            problems.append(
                f"{at}: {condition} has no parameter rule here. The schema's enum and this table "
                f"are two halves of one statement and both have to be extended together."
            )
            continue
        required, permitted = PARAMETER_RULES[condition]
        given = set(scenario["parameters"])
        for name in sorted(required - given):
            problems.append(f"{at}: {condition} needs the {name} parameter and it is not set")
        for name in sorted(given - permitted):
            problems.append(
                f"{at}: {name} is not a parameter of {condition}, so the injector would ignore it "
                f"and the scenario would read as though a dial had been set"
            )
    return problems


def check_targets_resolve(catalogue: dict) -> list[str]:
    known = declared_components() | INFRASTRUCTURE_TARGETS
    problems: list[str] = []
    for scenario in catalogue["scenarios"]:
        target = scenario["target"]
        if target not in known:
            problems.append(
                f"{scenario['scenarioId']}: degrades {target}, which is neither a component in "
                f"{SLO_CATALOGUE.relative_to(REPO)} nor one of the {len(INFRASTRUCTURE_TARGETS)} "
                f"infrastructure elements the fixture boots"
            )
    return problems


def check_the_window_fits_the_day(catalogue: dict) -> list[str]:
    """A condition that would still be held after midnight is one nothing reverts."""
    problems: list[str] = []
    for scenario in catalogue["scenarios"]:
        end = scenario["startsAtMinute"] + scenario["holdsForMinutes"]
        if end > 1440:
            problems.append(
                f"{scenario['scenarioId']}: starts at minute {scenario['startsAtMinute']} and is "
                f"held for {scenario['holdsForMinutes']}, which runs {end - 1440} minutes past the "
                f"end of the business day"
            )
    return problems


# --------------------------------------------------------------------------------------------


def load_module(path: pathlib.Path, name: str):
    """Import a sibling checker by path, so its controls are reused rather than restated."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    for path in (SCHEMA, CATALOGUE, SLO_CATALOGUE, VALIDATOR, ALERTING):
        if not path.exists():
            print(f"FAIL  {path.relative_to(REPO)} does not exist")
            return 1

    shared = load_module(VALIDATOR, "check_workload_model")
    alerting = load_module(ALERTING, "check_slo_catalogue")
    schema = json.loads(SCHEMA.read_text("utf-8"))
    catalogue = json.loads(CATALOGUE.read_text("utf-8"))

    try:
        problems = shared.validate(catalogue, schema, schema, "")
        problems += shared.check_schema_cannot_carry_personal_data(schema)
        problems += alerting.check_schema_carries_no_alerting(schema, shared.walk_schemas)
    except shared.UnimplementedKeyword as unimplemented:
        print(f"FAIL  {SCHEMA.relative_to(REPO)}")
        print(f"        {unimplemented}")
        print("        The JSON Schema subset asserted here is shared with check-workload-model.py")
        print("        and refuses to pass a schema it cannot fully assert. Implement the keyword")
        print("        in that one place, or drop it from this schema.")
        return 1

    if not problems:
        problems = (
            check_identifiers_are_unique(catalogue)
            + check_every_condition_is_covered(catalogue)
            + check_objectives_resolve(catalogue)
            + check_the_two_lists_are_disjoint(catalogue)
            + check_a_scenario_that_moves_nothing_says_why(catalogue)
            + check_parameters(catalogue)
            + check_targets_resolve(catalogue)
            + check_the_window_fits_the_day(catalogue)
        )

    if problems:
        print(f"FAIL  {CATALOGUE.relative_to(REPO)}")
        for problem in problems:
            print(f"        {problem}")
        return 1

    scenarios = catalogue["scenarios"]
    references = sum(
        len(scenario["movesObjectives"]) + len(scenario["flatObjectives"]) for scenario in scenarios
    )
    blind = sum(1 for scenario in scenarios if not scenario["movesObjectives"])
    print(
        f"OK    {CATALOGUE_ID}: {len(scenarios)} scenarios over "
        f"{len(REQUIRED_CONDITIONS)} conditions, {references} objective references resolved "
        f"against {SLO_CATALOGUE.relative_to(REPO)} "
        f"({blind} of them move nothing this estate has an objective for, each saying why)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
