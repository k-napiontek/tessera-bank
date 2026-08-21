#!/usr/bin/env python3
"""Validate the committed SLO catalogue, and then check the things a schema cannot say.

Three jobs.

The first is ordinary JSON Schema validation of contracts/slo/tessera-slo-v1.json against
contracts/slo/slo-catalogue.schema.json. The validator is **imported from check-workload-model.py
rather than copied**: this repository already asserts one hand-written subset of JSON Schema 2020-12,
and a second copy of it would be the duplication F-61, F-64 and F-66 each record rotting - in a place
where the two copies disagreeing would be silent. Importing it also inherits the property that makes
it worth having: an unrecognised keyword is a **failure rather than a silent pass**, because a
validator that ignores what it does not know agrees with a schema it never checked. The consequence
is deliberate: this catalogue's schema may only use vocabulary the shared subset already asserts.

The second is the arithmetic. A schema can say that a target is a number between 0 and 1; it cannot
say that the error budget beside it is one minus that target, nor that the minutes it permits are
that fraction of the measurement window. An error budget that has drifted from its objective is
worse than none, because both numbers look deliberate.

The third is the control ADR 0012 actually commits to:

    Every future component that emits a metric owes a catalogue entry, and the entry is part of its
    Definition of Done rather than a follow-up. A metric with no stated objective is the state this
    ADR exists to end, and adding one more of them silently would undo it.

So the check runs in both directions. Every metric this estate emits must appear in the catalogue,
and every metric the catalogue names must be emitted by the component it is filed under - read out
of that component's source rather than out of a transcription of it. A metric renamed in code and
left stale here fails, which is the drift ADR 0012 names as the more dangerous direction, "because
it looks like a control".

It does **not** check that a scrape carries the exposed name. For the Java tier the exposed name is
Micrometer's rendering of the meter name - dots to underscores, _total on a counter, the base unit
appended - and restating those rules here would be a second copy of somebody else's convention, and
a wrong copy would agree with itself. That half is asserted against a real scrape by the ledger's own
test. Each half is checked where it can actually be checked.

Standard library only, so it runs from a clean checkout with nothing installed.
"""

import importlib.util
import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = REPO / "contracts" / "slo" / "slo-catalogue.schema.json"
CATALOGUE = REPO / "contracts" / "slo" / "tessera-slo-v1.json"
VALIDATOR = REPO / "contracts" / "check-workload-model.py"

# --------------------------------------------------------------------------------------------
# Transcribed, not read out of the document being checked. A checker whose expectations come
# entirely from its own input agrees with every version of that input, including a wrong one. The
# same reasoning, and the same comment, as check-workload-model.py and check-break-report.py.
# --------------------------------------------------------------------------------------------
CATALOGUE_ID = "TB-SLO-CATALOGUE-V1"

#: Where a component of the bank may live. workload/ is deliberately absent - see NOT_A_COMPONENT.
SEARCH_ROOTS = ("services", "edge", "batch", "integration", "legacy")

#: Directories that emit metrics and owe no catalogue entry, each with the reason it is excused.
#: An exemption list with reasons is a decision somebody took; one without them is a hole.
NOT_A_COMPONENT = {
    "workload": (
        "a test fixture, not a component of the bank - the same category as estate-up.sh and "
        "walkthrough.sh. Its tessera_workload_* metrics describe the driver, and an objective for "
        "the thing doing the measuring would be a promise to nobody."
    ),
}

#: Label names that are unbounded in this estate. A metric labelled with one of these gives
#: Prometheus a time series per customer, which is the standard way to take a monitoring system down
#: and is discovered when the monitoring is needed most. Several are also personal data adjacent.
UNBOUNDED_TAGS = frozenset({
    "account", "account_ref", "accountref", "customer", "customer_ref", "customerref",
    "path", "uri", "url", "endpoint", "query",
    "reference", "entry_ref", "transfer_ref", "hold_ref", "idempotency_key",
    "correlation_id", "correlationid", "trace_id", "traceid", "span_id",
    "id", "key", "user", "email", "iban", "pan", "msisdn",
})

#: business_date grows one series a day in general. It is permitted on the batch components alone,
#: whose metrics go to a node_exporter textfile that each run rewrites - so exactly one date is ever
#: present. Anywhere else it is unbounded and refused.
DATE_TAG = "business_date"
DATE_TAG_ALLOWED_UNDER = "batch/"

#: Property names that must not exist in the schema. ADR 0012 puts the alert on the other side of a
#: line that is easy to agree with and easy to erode one convenient field at a time, so the erosion
#: is refused mechanically rather than in a review comment.
DENIED_PROPERTY_NAMES = frozenset({
    "burnrate", "burn_rate", "severity", "priority", "urgency",
    "receiver", "route", "routes", "notification", "notify", "channel", "escalation",
    "pagerduty", "opsgenie", "slack", "email", "webhook",
    "dashboard", "dashboards", "panel", "grafana",
    "recordingrule", "recording_rule", "alert", "alerts", "alertname", "alertrule",
    "retention", "replicas", "namespace", "cluster", "environment",
})

MINUTES_PER_DAY = 1440
TOLERANCE = 1e-9

# --------------------------------------------------------------------------------------------
# Reading what each component actually emits
# --------------------------------------------------------------------------------------------

#: Micrometer meter names in the Java tier are dotted string literals. The ledger prefixes every one
#: of its own with "ledger.", which is what makes them findable without parsing Java.
JAVA_METER = re.compile(r'"(ledger\.[a-z][a-z0-9.]*)"')

#: Go and Python both name a metric with the string a scrape will carry. Matching the literal rather
#: than the constructor around it keeps this working across the three different registries in use -
#: the gateway's prometheus/client_golang, the batch jobs' prometheus_client, and the workload
#: driver's hand-rolled exposition.
#:
#: Three segments at least, because the convention is tessera_<component>_<thing>_<unit> and a
#: two-segment literal is something else wearing the prefix: the first run of this checker matched
#: "tessera_ledger", which is the name of a database in a Testcontainers helper.
EXPOSED_METER = re.compile(r'"(tessera_[a-z][a-z0-9]*_[a-z][a-z0-9_]*)"')

SOURCE_SUFFIXES = (".java", ".go", ".py")


def is_source(path: pathlib.Path) -> bool:
    if not path.is_file() or path.suffix not in SOURCE_SUFFIXES:
        return False
    parts = [part.lower() for part in path.parts]
    if "__pycache__" in parts or "build" in parts or "target" in parts:
        return False
    # A test may name a metric in an assertion. Counting those would let a component satisfy the
    # catalogue with a metric it only ever mentions in a test. Both spellings of the directory are
    # excluded: Java puts its tests under src/test/, Python and Go under tests/.
    if "test" in parts or "tests" in parts:
        return False
    return "test" not in path.name.lower()


def strip_comments(text: str, suffix: str) -> str:
    """Remove line comments, so a metric named in prose is not mistaken for one that is emitted."""
    marker = "#" if suffix == ".py" else "//"
    return "\n".join(line.split(marker)[0] for line in text.splitlines())


def metrics_emitted_by(directory: pathlib.Path) -> set[str]:
    found: set[str] = set()
    for path in sorted(directory.rglob("*")):
        if not is_source(path):
            continue
        text = strip_comments(path.read_text("utf-8", errors="ignore"), path.suffix)
        found |= set(EXPOSED_METER.findall(text))
        if path.suffix == ".java":
            found |= set(JAVA_METER.findall(text))
    return found


def emitting_components() -> dict[str, set[str]]:
    """Every directory under a search root that emits at least one metric, and what it emits."""
    emitting: dict[str, set[str]] = {}
    for root in SEARCH_ROOTS:
        base = REPO / root
        if not base.is_dir():
            continue
        for directory in sorted(p for p in base.iterdir() if p.is_dir()):
            emitted = metrics_emitted_by(directory)
            if emitted:
                emitting[f"{root}/{directory.name}"] = emitted
    return emitting


# --------------------------------------------------------------------------------------------
# What the schema cannot say
# --------------------------------------------------------------------------------------------


def check_schema_carries_no_alerting(schema: dict, walk) -> list[str]:
    problems: list[str] = []
    for at, sub in walk(schema):
        for name in sub.get("properties", {}):
            if name.lower() in DENIED_PROPERTY_NAMES:
                problems.append(
                    f"{at}/properties/{name}: names something ADR 0012 puts in the platform "
                    f"repositories. The objective is declared here; the alert is configured there."
                )
    return problems


def check_arithmetic(catalogue: dict) -> list[str]:
    problems: list[str] = []
    for component in catalogue["components"]:
        for objective in component["objectives"]:
            at = f"{component['componentId']}/{objective['objectiveId']}"
            target = objective["target"]
            window = objective["windowDays"]
            budget = objective["errorBudget"]

            expected_fraction = 1 - target
            if abs(budget["fraction"] - expected_fraction) > TOLERANCE:
                problems.append(
                    f"{at}: an error budget of {budget['fraction']} against a target of {target}, "
                    f"which permits {expected_fraction:.10g}"
                )
                continue

            expected_minutes = expected_fraction * window * MINUTES_PER_DAY
            if abs(budget["minutesPerWindow"] - expected_minutes) > 1e-6:
                problems.append(
                    f"{at}: {budget['minutesPerWindow']} minutes against a budget of "
                    f"{budget['fraction']} over {window} days, which is {expected_minutes:.10g}"
                )
    return problems


def check_threshold_pairing(catalogue: dict) -> list[str]:
    """A threshold and the side of it that is good are one statement, and useless apart."""
    problems: list[str] = []
    for component in catalogue["components"]:
        for objective in component["objectives"]:
            at = f"{component['componentId']}/{objective['objectiveId']}"
            sli = objective["sli"]
            has_threshold = "threshold" in sli
            has_comparison = "comparison" in sli
            if has_threshold != has_comparison:
                problems.append(
                    f"{at}: a threshold without the comparison that says which side of it is good, "
                    f"or the other way round"
                )
            if sli["kind"] == "timeRatio" and not has_threshold:
                problems.append(
                    f"{at}: a timeRatio measures the proportion of the window a gauge stayed on the "
                    f"right side of a threshold, and names no threshold"
                )
    return problems


def check_tags(catalogue: dict) -> list[str]:
    problems: list[str] = []
    for component in catalogue["components"]:
        path = component["path"]
        for entry, at in catalogued_metrics(component):
            for tag in entry["tags"]:
                if tag.lower() in UNBOUNDED_TAGS:
                    problems.append(
                        f"{at}: the tag {tag!r} is unbounded in this estate - one time series per "
                        f"customer, discovered when the monitoring is needed most"
                    )
                elif tag == DATE_TAG and not path.startswith(DATE_TAG_ALLOWED_UNDER):
                    problems.append(
                        f"{at}: the tag {DATE_TAG!r} grows a series a day outside "
                        f"{DATE_TAG_ALLOWED_UNDER}, where a run rewrites its own textfile"
                    )
    return problems


def catalogued_metrics(component: dict):
    """Every metric a component's entry names, as (entry, where) pairs."""
    for objective in component["objectives"]:
        yield objective["sli"], f"{component['componentId']}/{objective['objectiveId']}"
    for signal in component["signals"]:
        yield signal, f"{component['componentId']}/{signal['meterName']}"


def check_identifiers_are_unique(catalogue: dict) -> list[str]:
    problems: list[str] = []
    seen_components: set[str] = set()
    seen_objectives: set[str] = set()
    for component in catalogue["components"]:
        name = component["componentId"]
        if name in seen_components:
            problems.append(f"{name}: two components share an id")
        seen_components.add(name)

        seen_meters: set[str] = set()
        for entry, at in catalogued_metrics(component):
            if entry["meterName"] in seen_meters:
                problems.append(f"{at}: {entry['meterName']!r} is catalogued twice")
            seen_meters.add(entry["meterName"])

        for objective in component["objectives"]:
            objective_id = objective["objectiveId"]
            if objective_id in seen_objectives:
                problems.append(f"{objective_id}: two objectives share an id")
            seen_objectives.add(objective_id)
    return problems


def check_coverage(catalogue: dict) -> list[str]:
    """Both directions: nothing emitted is uncatalogued, nothing catalogued is unemitted."""
    problems: list[str] = []
    emitting = emitting_components()
    catalogued = {component["path"]: component for component in catalogue["components"]}

    for path, emitted in sorted(emitting.items()):
        component = catalogued.get(path)
        if component is None:
            problems.append(
                f"{path}: emits {len(emitted)} metric(s) and has no catalogue entry. ADR 0012: a "
                f"metric with no stated objective is the state that ADR exists to end"
            )
            continue
        named = {entry["meterName"] for entry, _ in catalogued_metrics(component)}
        for metric in sorted(emitted - named):
            problems.append(f"{path}: emits {metric!r} and the catalogue does not mention it")
        for metric in sorted(named - emitted):
            problems.append(
                f"{path}: the catalogue names {metric!r} and nothing in that directory emits it - "
                f"either it was renamed in code, or the entry outlived the metric"
            )

    for path in sorted(set(catalogued) - set(emitting)):
        problems.append(f"{path}: catalogued, and no metric is emitted anywhere under it")

    return problems


def check_excluded_directories_still_exist() -> list[str]:
    """An exemption that outlives the thing it excuses is a hole nobody can see."""
    problems: list[str] = []
    for name in NOT_A_COMPONENT:
        directory = REPO / name
        if not directory.is_dir():
            problems.append(f"{name}/: excused from the catalogue and no longer exists")
        elif not metrics_emitted_by(directory):
            problems.append(
                f"{name}/: excused from the catalogue and emits no metric, so the exemption is "
                f"excusing nothing and should be withdrawn"
            )
    return problems


# --------------------------------------------------------------------------------------------


def load_validator():
    """The JSON Schema subset, imported from its one implementation rather than copied."""
    spec = importlib.util.spec_from_file_location("check_workload_model", VALIDATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    for path in (SCHEMA, CATALOGUE, VALIDATOR):
        if not path.exists():
            print(f"FAIL  {path.relative_to(REPO)} does not exist")
            return 1

    shared = load_validator()
    schema = json.loads(SCHEMA.read_text("utf-8"))
    catalogue = json.loads(CATALOGUE.read_text("utf-8"))

    try:
        problems = shared.validate(catalogue, schema, schema, "")
        problems += check_schema_carries_no_alerting(schema, shared.walk_schemas)
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
            + check_arithmetic(catalogue)
            + check_threshold_pairing(catalogue)
            + check_tags(catalogue)
            + check_coverage(catalogue)
            + check_excluded_directories_still_exist()
        )

    if problems:
        print(f"FAIL  {CATALOGUE.relative_to(REPO)}")
        for problem in problems:
            print(f"        {problem}")
        return 1

    objectives = sum(len(c["objectives"]) for c in catalogue["components"])
    signals = sum(len(c["signals"]) for c in catalogue["components"])
    print(
        f"OK    {CATALOGUE_ID}: {len(catalogue['components'])} components, "
        f"{objectives} objectives, {signals} supporting signals, "
        f"{objectives + signals} metrics accounted for"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
