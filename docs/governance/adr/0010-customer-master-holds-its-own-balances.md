# ADR 0010 - The system of record holds its own balances, and that duplication is the point

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** Karol Napiontek

## Context

`STATUS.md` has recorded since WP-06 that `Account` in the ledger stores no balance, because storing
one *"would create a second source of truth on day one - the exact drift `batch/recon` exists to
detect."* WP-10 now builds a component that stores exactly that, which looks like the same mistake
being made deliberately one stratum down.

It is not the same decision, and the difference is worth writing down before somebody reads the two
statements side by side and concludes one of them is wrong.

Three forces decide it:

- **The contract requires it.** `tb:Account` in `contracts/xsd/canonical-v1.xsd` makes
  `bookedBalance` and `availableBalance` mandatory elements, and `GetAccount` returns a `tb:Account`.
  The contract was authored in WP-02 and is the source of truth; an implementation that omitted the
  element would not satisfy it.
- **Stratum 1 cannot ask stratum 3.** There is no dependency from `legacy/` to `services/` and there
  must not be one - it would point backwards through the estate, and a 2011 monolith calling a 2023
  REST service to answer a question about its own accounts is not a system anyone built. The
  monolith predates the ledger by twelve years.
- **`NotifyTransferPosted` only makes sense if it applies something.** Its own documentation in the
  WSDL says a duplicate is *"acknowledged, not double-applied"*. There is nothing to double-apply
  unless the operation moves a balance.

## Decision

**`legacy/customer-master` maintains its own booked balance per account, applied by
`PKG_POSTING.apply_transfer` when the ledger notifies it that a transfer posted.**

The estate therefore holds the same money in three places - the COBOL account master, the ledger, and
this component - which is precisely the condition a real bank operates under, and precisely what
`batch/recon` (WP-16) exists to reconcile. The duplication is not a compromise reached under
pressure; it is the artefact the reconciliation tier is built to work on. An estate with one balance
has nothing to reconcile and teaches nothing about why reconciliation is the hardest job in a bank.

Two consequences of that decision are stated here because both look like defects otherwise.

**Status is not consulted when applying a posting.** A `CLOSED` or `BLOCKED` account is credited or
debited like any other. `NotifyTransferPosted` reports a movement the ledger has **already made**;
refusing it does not unmake the movement, it only leaves this master permanently wrong about that
account - and worse, it hides the disagreement from reconciliation, because a refused transfer is
never recorded as applied. A block belongs before a payment, where it can still prevent one. What the
procedure does refuse is an integrity failure: an account it has never heard of, a currency that
cannot be added to the balance it is aimed at, a non-positive amount, or both legs naming one
account. Those mean the two systems disagree about the world rather than about money, and WP-11's
dead-letter path exists for them.

**`availableBalance` equals `bookedBalance` here.** A hold lives in the ledger, and no notification
in this contract carries one, so this component cannot know what is held. The contract makes the
element mandatory, so the choice is between a defensible number and an invented one.

## Consequences

- The three balances **will** drift, and detecting that is WP-16's job rather than a failure of this
  component. Until WP-16 exists, nothing compares them - which is worth knowing before anyone reads a
  figure from this tier as authoritative.
- The ledger remains the authority on whether a posting happened. This component mirrors postings; it
  does not decide them. Any future operation here that *originates* a movement would break that and
  needs its own decision.
- `availableBalance` from this tier must not be presented to a customer as spendable. `REQ-UI-003`
  is satisfied by `web-banking` reading the ledger, which knows about holds. A future consumer
  reading this tier instead would satisfy the schema and violate the requirement.
- Reconciliation has something real to find. The synthetic estate can be made to drift on purpose,
  which is what WP-18's incident exercise needs.

## Alternatives considered

**Store no balance and call the ledger.** Rejected: it inverts the estate's dependency direction, and
it makes a 2011 component's availability depend on a 2023 one. It also answers the wrong question -
this component is the system of record for account *metadata*, and a system of record that cannot
answer without asking somebody else is not one.

**Return zero, or omit the element.** Rejected: omitting it fails schema validation, and returning
zero is a number that means "no money" rather than "not known here". The failure mode of a plausible
wrong number is the one this repository takes most seriously.

**Store `available_balance` as its own column.** Rejected. Nothing in this tier could ever make it
differ from `booked_balance`, so it would be a column whose only possible future is to disagree with
the one beside it. The accessor computes it and the README says why.
