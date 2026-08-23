# PSD2 notes

Where the second Payment Services Directive would apply to this estate, and what it would require.

**Notes rather than an implementation**, and deliberately so. The strong customer authentication and
consent surfaces sit largely in components this repository keeps out of initial scope, and the parts
that do exist are shaped by the estate's own priorities rather than by PSD2's. This document says
which is which, because a compliance note that describes the regulation and stays quiet about the
gap is the least useful kind.

PSD2 is Directive (EU) 2015/2366, with the operative detail in the **RTS on strong customer
authentication and common and secure communication** (Commission Delegated Regulation (EU) 2018/389),
applicable since September 2019. A successor package - a PSD3 directive and a payment services
regulation - has been in the EU legislative process since 2023; nothing here should be read as
settled law, and the shapes below are the ones that have been stable for years.

---

## Strong customer authentication

**When it applies.** SCA is required when a payment service user accesses their account information
online, initiates an electronic payment transaction, or performs any action through a remote channel
that implies a risk of payment fraud.

**What it is.** Two or more elements from three independent categories - **knowledge** (something
only the user knows), **possession** (something only the user has), **inherence** (something the user
is) - independent in the sense that breaching one does not compromise the others.

**Dynamic linking** is the part most often missed: for a remote payment, the authentication code must
be **specific to the amount and the payee**, both must be shown to the user at the point of
authentication, and any change to either must invalidate the code.

**Where it would sit here.** At [`edge/api-gateway`](../../edge/api-gateway/README.md), which is
already where "authentication happens once, at the edge" (`REQ-EDG-001`), with the amount-and-payee
display in [`edge/web-banking`](../../edge/web-banking/README.md)'s transfer flow.

**What this estate actually has.** Bearer-token validation with the algorithm pinned, coarse
authorisation by scope, and no user authentication flow at all - no credential store, no second
factor, no dynamic linking. That is a deliberate boundary rather than an oversight: implementing
authentication properly means holding credentials, and this repository holds **no personal data and
no authentication material by policy**
([`../ways-of-working/data-classification.md`](../ways-of-working/data-classification.md)). The token
the gateway validates is minted by a fixture for local running.

## Exemptions

The RTS permits SCA to be skipped in defined cases. They matter because **the exemption logic is
where the engineering lives** - the SCA flow itself is comparatively simple, and deciding whether it
is needed is not.

| Exemption | Shape |
|---|---|
| Low-value remote payments | Below a per-transaction threshold, with cumulative amount and count limits since the last SCA |
| Contactless at point of sale | Below a per-transaction threshold, with its own cumulative limits |
| Trusted beneficiaries | A payee the user has added to a list held by the account servicing provider |
| Recurring transactions | Same amount, same payee; SCA on the first, not on the series |
| Payments to self | Between two accounts held by the same user at the same institution - a member-state option |
| Transaction risk analysis | Below a threshold that depends on the provider's **measured fraud rate** |

**The cumulative counters are the hard part**, and they are stateful per user across channels and
time. This estate has nowhere to keep them: `services/ledger-core` holds account references and no
customer identity at all, deliberately, and the counter belongs to the authentication domain rather
than to the ledger.

**Payments to self is the exemption this estate would actually use most**, because the spine it is
built around - `REQ-UI-001`, a customer transferring between their own accounts - is precisely that
case.

## Transaction risk analysis, and where `fraud-scoring` does not fit

The TRA exemption lets a provider skip SCA on a transaction its real-time risk analysis judges low
risk, provided the provider's own fraud rate for that payment instrument stays under a published
reference rate - lower rates buying higher thresholds. It requires monitoring, calculating and
reporting that fraud rate.

[`edge/fraud-scoring`](../../edge/fraud-scoring/README.md) is the component that looks like it would
serve this, and **it would not, by construction**:

- It consumes the ledger's `transfer-posted` event, so it scores a payment that **has already moved**.
  A TRA decision has to be made *before* authorisation.
- `REQ-FRD-001` states the property directly: **scoring never blocks money movement.** That is the
  right design for this estate - a scorer in the money path is a scorer that becomes an outage - and
  it is the opposite of what an exemption decision needs.
- Its rules are pure functions of one event, with no view of the user's history, no cumulative
  counters and no fraud-rate calculation.

Where it *does* line up: the decision is explainable and reproducible from a recorded version
(`REQ-FRD-002`, `REQ-FRD-003`), which is the property a supervisor asks about when a customer
disputes an outcome. **A TRA implementation would be a second, synchronous component**, sharing the
rule vocabulary and nothing else.

## Third-party access

PSD2 obliges an account servicing provider to let authorised third parties act for the user:
**AISP** (account information), **PISP** (payment initiation), **CBPII** (funds confirmation). The
requirements that follow are not mainly about the API:

- **A dedicated interface** for third parties, with identification by eIDAS certificates.
- **Availability and performance at least equal to the customer interface**, measured and published
  as quarterly statistics.
- **A testing facility** for third parties, and a **contingency mechanism** unless the national
  authority exempts the provider from it.
- **Consent** obtained by the third party, and the account provider verifying access rights rather
  than the consent itself.

**None of this exists here.** [`contracts/openapi/ledger-core.yaml`](../../contracts/openapi/README.md)
is an internal contract between the gateway and the ledger; it is not a PSD2 dedicated interface and
should not be mistaken for one. Two things do transfer if one were built: the estate already declares
its interfaces as contracts before implementing them (`REQ-INT-001`), and it already states
objectives per service in the [SLO catalogue](../ways-of-working/slo-catalogue.md) - which is the
form the availability obligation takes.

## What this repository implements, notes, and skips

| | |
|---|---|
| **Implements** | Nothing PSD2-specific. Idempotent money movement, an explainable fraud decision and one-place edge authentication are properties PSD2 would need, built for their own reasons |
| **Notes** | This document: where each obligation would land, and what it would cost |
| **Skips, deliberately** | SCA and dynamic linking, exemption counters, consent management, the dedicated interface and its statistics |

The reason is the same one that keeps `services/payment-engine` and the cards tier on the estate map
and out of the build: **the estate is deep in one flow rather than shallow across many**, and a
half-built SCA surface would be the least honest thing in the repository - it would look like a
control and behave like a demonstration.

Where PSD2's obligations overlap DORA's, the mapping is in
[`dora-control-map.md`](dora-control-map.md); the data-protection side is
[`gdpr-data-map.md`](gdpr-data-map.md).
