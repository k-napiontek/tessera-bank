#!/usr/bin/env python3
"""Validate the committed workload model against its schema, and then check what a schema cannot.

Two jobs, deliberately in one place.

The first is ordinary JSON Schema validation of contracts/workload/tessera-day-v1.json against
contracts/workload/workload-model.schema.json - done by a hand-written checker rather than by a
library, for the reason the two checks either side of this one give: contracts/validate.sh runs from
a clean checkout and its dependency set stays as it is. The subset implemented below is exactly the
keywords the schema uses, and an unrecognised keyword is a **failure rather than a silent pass**. A
validator that ignores what it does not know agrees with a schema it never checked, which is the same
class of defect as a test suite that prints PASS while hiding a compiler warning (F-20).

The second is the arithmetic. A schema can say that a weight is a number between 0 and 1; it cannot
say that four of them add to exactly 1, that the population multiplied by its per-customer rates is
the daily volume the model claims, or that the busiest hour divided by the quietest is the ratio
declared beside the curve. Those are the statements a hand-maintained document silently loses, and
every one of them is checked here.

It does not prove that a schedule the engine emits follows the model. That is workload/'s own test,
and the two claims are checked from opposite sides on purpose - the same division as
check-extract-layout.py and check-break-report.py next door.

Standard library only, so it runs from a clean checkout with nothing installed.
"""

import json
import math
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = REPO / "contracts" / "workload" / "workload-model.schema.json"
MODEL = REPO / "contracts" / "workload" / "tessera-day-v1.json"
OPENAPI = REPO / "contracts" / "openapi" / "ledger-core.yaml"

# --------------------------------------------------------------------------------------------
# Transcribed from the model, not read out of it. A checker whose expectations come entirely from
# the document it is checking agrees with every version of that document, including a wrong one.
# The same reasoning, and the same comment, as check-break-report.py.
# --------------------------------------------------------------------------------------------
MODEL_ID = "TB-WORKLOAD-DAY-V1"
HOURS = 24
COHORT_IDS = ("retail", "retail-affluent", "small-business", "corporate")
REQUIRED_WINDOWS = ("overnight-batch", "morning-reconciliation")
REQUIRED_INSTANTS = ("online-cut-off",)

#: Currencies this estate can carry end to end. Stratum 0 stores an amount as PIC S9(13)V99 COMP-3,
#: which is scale 2 and cannot represent JPY (scale 0) or BHD (scale 3) - see section 2 of
#: docs/architecture/canonical-data-model.md, and follow-up F-09. A model naming one of those would
#: describe demand the integration tier is required to reject before it reaches the mainframe, which
#: is a fault-injection scenario (WP-24) rather than a day.
CARRIABLE_CURRENCIES = ("CHF", "EUR", "GBP", "PLN", "USD")

#: Property names that must not exist anywhere in the schema. The Definition of Done requires that
#: the schema be *incapable* of expressing personal data; a denylist checked against the schema is
#: how that becomes a control rather than an intention.
DENIED_PROPERTY_NAMES = (
    "name", "firstname", "lastname", "surname", "fullname", "holder", "holdername",
    "email", "phone", "msisdn", "address", "postcode", "city", "street",
    "dateofbirth", "dob", "nationalid", "pesel", "nip", "regon", "ssn", "taxid",
    "iban", "pan", "cardnumber", "cvv", "password", "secret", "token",
)

#: String-typed fields allowed to be free prose, because they describe the model rather than carry
#: data. Everything else must be bounded by an enum, a const or a pattern.
PROSE_FIELDS = ("summary", "purpose")

TOLERANCE = 1e-9

OPERATION_ID = re.compile(r"^\s+operationId:\s*([A-Za-z][A-Za-z0-9]*)\s*$")

# --------------------------------------------------------------------------------------------
# The JSON Schema subset
# --------------------------------------------------------------------------------------------

#: Keywords carrying no assertion. Present so that meeting one is not mistaken for meeting an
#: unimplemented assertion.
ANNOTATIONS = frozenset({"$schema", "$id", "$comment", "title", "description", "$defs"})

APPLICATORS = frozenset(
    {
        "$ref", "type", "const", "enum",
        "properties", "required", "additionalProperties", "propertyNames",
        "minProperties", "maxProperties",
        "items", "minItems", "maxItems",
        "pattern", "minLength", "maxLength",
        "minimum", "maximum", "exclusiveMinimum",
    }
)


class UnimplementedKeyword(Exception):
    """The schema uses a keyword this checker does not assert. Never ignored."""


def is_integer(value: object) -> bool:
    # bool is a subclass of int in Python, and a boolean is not an integer in JSON Schema.
    return isinstance(value, int) and not isinstance(value, bool)


def is_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


TYPE_CHECKS = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "integer": is_integer,
    "number": is_number,
    "boolean": lambda v: isinstance(v, bool),
}


def resolve(schema: dict, root: dict) -> dict:
    """Follow a local $ref. Only #/$defs/NAME is supported, which is all the schema uses."""
    ref = schema["$ref"]
    match = re.fullmatch(r"#/\$defs/([A-Za-z][A-Za-z0-9]*)", ref)
    if not match:
        raise UnimplementedKeyword(f"$ref {ref!r} is not a local #/$defs reference")
    name = match.group(1)
    if name not in root.get("$defs", {}):
        raise UnimplementedKeyword(f"$ref {ref!r} resolves to nothing")
    return root["$defs"][name]


def validate(value: object, schema: dict, root: dict, at: str) -> list[str]:
    """Assert `value` against `schema`. Returns problems, each carrying a JSON pointer."""
    unknown = set(schema) - ANNOTATIONS - APPLICATORS
    if unknown:
        raise UnimplementedKeyword(f"{at or '/'}: schema uses {sorted(unknown)}")

    if "$ref" in schema:
        return validate(value, resolve(schema, root), root, at)

    problems: list[str] = []

    if "type" in schema:
        kind = schema["type"]
        if not TYPE_CHECKS[kind](value):
            return [f"{at or '/'}: expected {kind}, found {type(value).__name__}"]

    if "const" in schema and value != schema["const"]:
        problems.append(f"{at or '/'}: must be {schema['const']!r}, found {value!r}")
    if "enum" in schema and value not in schema["enum"]:
        problems.append(f"{at or '/'}: {value!r} is not one of {schema['enum']}")

    if isinstance(value, str):
        if "pattern" in schema and not re.search(schema["pattern"], value):
            problems.append(f"{at}: {value!r} does not match {schema['pattern']}")
        if "minLength" in schema and len(value) < schema["minLength"]:
            problems.append(f"{at}: shorter than {schema['minLength']} characters")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            problems.append(f"{at}: longer than {schema['maxLength']} characters")

    if is_number(value):
        if "minimum" in schema and value < schema["minimum"]:
            problems.append(f"{at}: {value} is below the minimum {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            problems.append(f"{at}: {value} is above the maximum {schema['maximum']}")
        if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
            problems.append(f"{at}: {value} must be above {schema['exclusiveMinimum']}")

    if isinstance(value, dict):
        problems += validate_object(value, schema, root, at)
    if isinstance(value, list):
        problems += validate_array(value, schema, root, at)

    return problems


def validate_object(value: dict, schema: dict, root: dict, at: str) -> list[str]:
    problems: list[str] = []
    properties = schema.get("properties", {})

    for name in schema.get("required", []):
        if name not in value:
            problems.append(f"{at}/{name}: required and absent")

    if "minProperties" in schema and len(value) < schema["minProperties"]:
        problems.append(f"{at}: fewer than {schema['minProperties']} properties")
    if "maxProperties" in schema and len(value) > schema["maxProperties"]:
        problems.append(f"{at}: more than {schema['maxProperties']} properties")

    extra = schema.get("additionalProperties", True)
    for key, item in value.items():
        where = f"{at}/{key}"
        if "propertyNames" in schema:
            problems += validate(key, schema["propertyNames"], root, f"{where} (name)")
        if key in properties:
            problems += validate(item, properties[key], root, where)
        elif extra is False:
            problems.append(f"{where}: not declared, and the object is closed")
        elif isinstance(extra, dict):
            problems += validate(item, extra, root, where)

    return problems


def validate_array(value: list, schema: dict, root: dict, at: str) -> list[str]:
    problems: list[str] = []
    if "minItems" in schema and len(value) < schema["minItems"]:
        problems.append(f"{at}: fewer than {schema['minItems']} items")
    if "maxItems" in schema and len(value) > schema["maxItems"]:
        problems.append(f"{at}: more than {schema['maxItems']} items")
    if "items" in schema:
        for index, item in enumerate(value):
            problems += validate(item, schema["items"], root, f"{at}/{index}")
    return problems


# --------------------------------------------------------------------------------------------
# What the schema cannot say
# --------------------------------------------------------------------------------------------


def walk_schemas(schema: dict, at: str = ""):
    """Every subschema in the document, with its pointer."""
    yield at, schema
    for key in ("properties", "$defs"):
        for name, sub in schema.get(key, {}).items():
            if isinstance(sub, dict):
                yield from walk_schemas(sub, f"{at}/{key}/{name}")
    for key in ("items", "propertyNames", "additionalProperties"):
        sub = schema.get(key)
        if isinstance(sub, dict):
            yield from walk_schemas(sub, f"{at}/{key}")


def check_schema_cannot_carry_personal_data(schema: dict) -> list[str]:
    problems: list[str] = []
    for at, sub in walk_schemas(schema):
        for name in sub.get("properties", {}):
            if name.lower() in DENIED_PROPERTY_NAMES:
                problems.append(f"{at}/properties/{name}: a denied property name")
        if sub.get("type") == "object" and "additionalProperties" not in sub:
            problems.append(f"{at or '/'}: an object with no additionalProperties - it is open")
        if sub.get("type") == "string":
            bounded = {"enum", "pattern", "const"} & set(sub)
            if not bounded and at.rsplit("/", 1)[-1] not in PROSE_FIELDS:
                problems.append(f"{at}: an unbounded string that is not declared prose")
    return problems


def declared_operations() -> set[str]:
    """The operationIds the ledger genuinely serves, read from its OpenAPI document.

    A regex rather than a YAML parser, because operationId is a top-level key of an operation object
    and appears nowhere else in that file. The alternative is a dependency, and validate.sh runs from
    a clean checkout.
    """
    return {
        match.group(1)
        for match in (OPERATION_ID.match(line) for line in OPENAPI.read_text("utf-8").splitlines())
        if match
    }


def sums_to_one(weights: dict[str, float]) -> bool:
    return math.isclose(sum(weights.values()), 1.0, abs_tol=TOLERANCE)


def check_model(model: dict) -> list[str]:
    problems: list[str] = []

    if model["modelId"] != MODEL_ID:
        problems.append(f"modelId is {model['modelId']!r}, expected {MODEL_ID!r}")

    calendar = model["calendar"]
    diurnal = calendar["diurnal"]
    if len(diurnal) != HOURS:
        problems.append(f"the diurnal curve has {len(diurnal)} hours, expected {HOURS}")
    else:
        ratio = max(diurnal) / min(diurnal)
        declared = model["realTime"]["peakToTroughRatio"]
        if not math.isclose(ratio, declared, rel_tol=1e-9):
            problems.append(
                f"the curve's peak-to-trough ratio is {ratio:.6f}, "
                f"and realTime.peakToTroughRatio says {declared}"
            )

    for kind, key, required in (
        ("window", "windows", REQUIRED_WINDOWS),
        ("instant", "instants", REQUIRED_INSTANTS),
    ):
        ids = [entry["id"] for entry in calendar[key]]
        if len(ids) != len(set(ids)):
            problems.append(f"{key}: duplicate ids in {ids}")
        for wanted in required:
            if wanted not in ids:
                problems.append(f"{key}: no {kind} called {wanted!r}")
    for window in calendar["windows"]:
        if window["startMinute"] == window["endMinute"]:
            problems.append(
                f"window {window['id']!r} starts and ends at minute {window['startMinute']}; "
                f"a window of no length is an instant and belongs in instants"
            )

    population = model["population"]
    cohorts = population["cohorts"]
    ids = tuple(cohort["id"] for cohort in cohorts)
    if sorted(ids) != sorted(COHORT_IDS):
        problems.append(f"cohorts are {sorted(ids)}, expected {sorted(COHORT_IDS)}")

    shares = {cohort["id"]: cohort["share"] for cohort in cohorts}
    if not sums_to_one(shares):
        problems.append(f"cohort shares add to {sum(shares.values())!r}, not 1")

    operations = declared_operations()
    generated = 0
    for cohort in cohorts:
        cid = cohort["id"]
        members = population["size"] * cohort["share"]
        if not math.isclose(members, round(members), abs_tol=1e-6):
            problems.append(
                f"cohort {cid!r}: share {cohort['share']} of {population['size']} customers "
                f"is {members}, which is not a whole number of people"
            )
        generated += round(members) * cohort["eventsPerCustomerPerDay"]

        if not sums_to_one(cohort["currencyMix"]):
            problems.append(
                f"cohort {cid!r}: currency weights add to {sum(cohort['currencyMix'].values())!r}"
            )
        for code in cohort["currencyMix"]:
            if code not in CARRIABLE_CURRENCIES:
                problems.append(
                    f"cohort {cid!r}: currency {code} is not one this estate carries end to end "
                    f"({', '.join(CARRIABLE_CURRENCIES)}) - stratum 0 stores scale 2 only"
                )

        if not sums_to_one(cohort["operationMix"]):
            problems.append(
                f"cohort {cid!r}: operation weights add to {sum(cohort['operationMix'].values())!r}"
            )
        for operation in cohort["operationMix"]:
            if operation not in operations:
                problems.append(
                    f"cohort {cid!r}: {operation!r} is not an operationId in "
                    f"contracts/openapi/ledger-core.yaml"
                )

        amount = cohort["amount"]
        if not amount["minMinor"] <= amount["medianMinor"] <= amount["maxMinor"]:
            problems.append(
                f"cohort {cid!r}: amount median {amount['medianMinor']} is outside "
                f"[{amount['minMinor']}, {amount['maxMinor']}]"
            )

    declared = model["realTime"]["dailyEventCount"]
    if generated != declared:
        problems.append(
            f"the population generates {generated:,} events a day and "
            f"realTime.dailyEventCount declares {declared:,}"
        )

    return problems


def main() -> int:
    for path in (SCHEMA, MODEL, OPENAPI):
        if not path.exists():
            print(f"FAIL  {path.relative_to(REPO)} does not exist")
            return 1

    schema = json.loads(SCHEMA.read_text("utf-8"))
    model = json.loads(MODEL.read_text("utf-8"))

    try:
        problems = validate(model, schema, schema, "")
        problems += check_schema_cannot_carry_personal_data(schema)
    except UnimplementedKeyword as unimplemented:
        print(f"FAIL  {SCHEMA.relative_to(REPO)}")
        print(f"        {unimplemented}")
        print("        This checker asserts a fixed subset of JSON Schema 2020-12 and refuses to")
        print("        pass a schema it cannot fully assert. Implement the keyword or drop it.")
        return 1

    if not problems:
        problems = check_model(model)

    if problems:
        print(f"FAIL  {MODEL.relative_to(REPO)}")
        for problem in problems:
            print(f"        {problem}")
        return 1

    population = model["population"]
    print(
        f"OK    {MODEL_ID}: {len(population['cohorts'])} cohorts, "
        f"{population['size']:,} customers, "
        f"{model['realTime']['dailyEventCount']:,} events a day, "
        f"peak/trough {model['realTime']['peakToTroughRatio']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
