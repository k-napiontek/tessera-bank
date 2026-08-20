# ADR 0013 - Expose the customer master as a contract-first SOAP service

**Status:** Accepted
**Date:** 2011-06-09
**Deciders:** Integration Architecture Board, Tessera Bank

> **Back-dated ADR - a historical reconstruction.** This decision was taken in 2011 and this document
> reproduces the reasoning of that year, in the idiom of that year. The date is not a real one. See
> [Back-dated ADRs](README.md#back-dated-adrs). A closing note written in 2026 records what actually
> followed.

## Context

The Customer Master holds the definitive record of every customer and every account: who they are,
what they hold, and what the balance is. Four programmes now need to read it and one needs to tell it
that money has moved. Today each of them reaches into the schema with its own DBA-issued account and
its own SQL, and every schema change requires a co-ordinated release across all five.

That has to stop. The question before the board is what replaces it.

The constraints are ordinary ones for this bank:

- **Consumers are heterogeneous and will stay that way.** The ESB is Java. The branch systems are
  .NET. The card scheme gateway is C. Whatever is chosen has to have mature client tooling on all
  three, generated rather than hand-written, because hand-written integration code is where our
  incident log lives.
- **The interface must be reviewable before it is built.** Interface changes go to the Change
  Advisory Board, and the board reviews documents, not source. An interface that can only be
  inspected by reading the provider's code cannot be governed.
- **Non-repudiation and message-level security are on the roadmap.** Payment instructions will need
  signing, and signing at the message level rather than at the transport, because a message crosses
  three hops between the branch and the core and TLS protects only one at a time.
- **The provider runs on Java EE.** The application server, the skills in the team, and the vendor
  support contract are all Java EE, and this is a system that will be maintained for fifteen years by
  people who have not met us.

## Decision

Expose the Customer Master as a **SOAP 1.1 web service over HTTP, document/literal wrapped, defined
WSDL-first**.

Concretely:

1. **The WSDL is authored by hand and is the deliverable.** It is written, reviewed and approved
   before any Java exists, and it is versioned in its own right - `customer-master-v1.wsdl`.
2. **The implementation is generated from the contract.** `wsimport` produces the service endpoint
   interface; the implementation class implements that interface. It therefore cannot compile unless
   it answers exactly the operations the contract declares.
3. **Business types are imported, never redefined.** The WSDL declares only the operation wrappers
   and the fault. Everything else - Account, Money, Transfer, Movement - comes from the canonical
   schema, so there is exactly one definition of what an Account is across the estate.
4. **Document/literal wrapped**, per WS-I Basic Profile 1.1. RPC/encoded is not interoperable in
   practice and is prohibited by the profile; bare document style loses the operation-name-equals-
   wrapper-element convention that every client toolkit relies on to produce a sane method signature.
5. **Faults are declared in the contract**, as a typed fault element, so a consumer can distinguish
   a business refusal from a transport failure without parsing prose.

The alternative to generation is generating the WSDL from annotated Java. We are not doing that, and
the reason is worth stating plainly: **a WSDL produced by reflecting over Java classes is not a
contract.** It is a description of whatever the code happened to be that day. It changes when a
developer renames a parameter, and the first anyone hears of it is a consumer's marshalling error in
production.

## Consequences

**Easier.**

- Consumers generate a typed client in a day, in any of the three languages, from a document we
  publish.
- The interface can be reviewed and approved by people who do not read Java.
- The XML Schema does real validation work at the boundary: a malformed account reference or a
  decimal amount is refused by the parser, before a line of our code runs.
- WS-Security, WS-Addressing and WS-ReliableMessaging are available when the roadmap reaches them,
  without changing the interface style.
- The schema, not the implementation, decides what a valid response is - so conformance is
  something that can be tested rather than reviewed.

**Harder.**

- SOAP is verbose. An account lookup that could be 200 bytes will be nearer 2 000. At the volumes
  this service sees, that is acceptable; if it ever ceases to be, the answer is a caching consumer,
  not a different interface style.
- A contract change is a release event. That is the intended cost, but it will be felt by anyone who
  wants a new field this quarter.
- Generated code must never be committed. If it is, the next developer edits the copy and the
  contract silently stops being the source of truth. This needs to be enforced by the build.
- The team must keep the WSDL and the canonical schema in step by hand. There is no compiler for
  the relationship between two XML documents.

**Committed to.** The contract, not the code, is the interface. Any change to what this service
exposes starts as a change to the WSDL, goes to the board, and only then reaches an implementation.

## Alternatives considered

**Continue with direct database access.** Rejected. It is the status quo and it is why this paper
exists: five consumers coupled to a physical schema, no way to change a column, and no audit of who
read what.

**A proprietary RPC binding (RMI, or the application server's own remoting).** Rejected on
heterogeneity. It would serve the Java consumers well and leave the .NET and C ones writing bridges,
which is where the defects would then live.

**A message-only interface over the MQ infrastructure.** Rejected for the read operations, which are
request-response and synchronous by nature; a branch teller waiting on a queue round trip is a
support call. Retained for the notification path in principle - which is why
`NotifyTransferPosted` is specified as idempotent, so that it can be driven from an at-least-once
transport later without redesign.

**REST over JSON.** Genuinely considered and rejected, for 2011 reasons that were sound at the time.
There is no schema language for JSON that any of our toolchains can validate against, so the contract
would be prose and the validation would be hand-written in every consumer. There is no equivalent of
WS-Security for message-level signing. And the client tooling is immature outside the scripting
languages, so the .NET and C consumers would hand-write HTTP calls - which returns us to the class of
defect this decision exists to remove. Revisit when the ecosystem has a schema and a security story.

---

## Closing note, 2026

Recorded when WP-10b implemented this interface, fifteen years after the decision.

The decision held, and the estate around it did not. REST arrived with a schema language and a
security story, and `services/ledger-core` is REST - but nothing replaced this service, because
replacing a system of record has never cleared a business case. So the two coexist, and
`integration/esb-adapter` exists to bridge them. That is the ordinary outcome, and it is what
[ADR 0002](0002-deliberate-legacy-strata.md) is about.

Two of the 2011 predictions are worth marking as scored:

- **"Generated code must never be committed ... this needs to be enforced by the build."** It now is:
  `GeneratedCodeTest` fails the build if a generated artefact appears under `src/main/java`.
- **"There is no compiler for the relationship between two XML documents."** Still true, and now
  covered by tests instead - `SoapResponseConformanceTest` validates every response against the
  canonical schema, and `DeploymentDescriptorTest` holds the WSDL, the deployment descriptors and
  the endpoint's JNDI lookup to the same strings.

One prediction was wrong in an instructive direction. The 2011 paper says faults are "declared in the
contract, so a consumer can distinguish a business refusal from a transport failure". The fault
*element* is declared; the fault **codes** are not - `faultCode` is an `xs:string` and only
`ACCT_NOT_FOUND` is named, in prose, in a `wsdl:documentation`. The implementation raises four. A
consumer must therefore hard-code what it does with each, which is the coupling this decision was
meant to remove, reintroduced one level down. Recorded as **F-51**, and the same shape as **F-34** at
the edge tier - which is now two occurrences and an argument that a fault or error surface is a
contract artefact rather than a README one.

This ADR gives [TD-004](../../technical-debt.md) the reasoning it had been asserting without.
