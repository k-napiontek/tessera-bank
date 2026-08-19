# WP-14 - web-banking

| | |
|---|---|
| **Ticket** | TB-1014 |
| **Branch** | `feat/TB-1014-web-banking` |
| **Stratum** | 4 - TypeScript + React, ~2025 |
| **Depends on** | WP-12 |
| **Status** | `Not started` |

## Objective

The customer-facing web application: view accounts and balances, read a statement, and make an
internal transfer. It is the entry point of the spine flow, and its job is to make the transfer
journey correct and honest - particularly around the states that banking UIs habitually get wrong,
such as a submitted transfer whose outcome is not yet known.

## In scope

- Account list, balance view distinguishing **booked** from **available**, and a paginated statement.
- Transfer form with validation, confirmation step and result state.
- Idempotency key generated client-side per transfer attempt and reused across retries.
- Error handling that renders RFC 9457 Problem Details meaningfully rather than showing a raw status
  code.
- Accessible, responsive layout.

## Out of scope

- Authentication screens beyond what is needed to obtain a token from the gateway.
- Any direct call to `ledger-core` - everything goes through the gateway.
- The internal operations UI - that is `backoffice`, WP-15, and it is deliberately from a different
  era.

## Constraints

- TypeScript strict mode. No `any` reaching a component boundary.
- The client generates the `Idempotency-Key` once per transfer attempt and **reuses it on retry**. A
  fresh key on retry would move money twice, which is precisely the bug idempotency exists to prevent.
- Booked and available balances must be visibly distinct. Showing one number where a hold exists
  misleads the customer about what they can actually spend.
- A submitted transfer whose response is lost must present as pending and be resolvable by query,
  never silently as success or failure.
- No personal data in browser storage or console output.

## Tasks

Detailed 2026-08-19. Two gaps in this package's own premises were found while planning, and both are
answered here rather than papered over, because each changes what gets built:

- **No endpoint enumerates a customer's accounts.** The In-scope section asks for an "account list",
  and `contracts/openapi/ledger-core.yaml` offers `getAccount`, `getBalance` and `getStatement`
  against a reference the caller already holds - nothing that lists. The gateway's route table mirrors
  it exactly, and the token carries a pseudonymous `sub` with coarse scopes, not an account set.
  Adding the endpoint means editing WP-02's contract, WP-08's service and WP-12's route table from a
  WP-14 branch, which is three packages' work on one branch. So the session is **told** its account
  references when it signs in, and the dashboard fans out `getAccount` + `getBalance` over them. The
  app enumerates nothing it was not given. Logged as a follow-up.
- **Nothing in the estate issues a token.** ADR 0007 is explicit that the gateway validates and
  forwards and mints nothing, because an edge component holding a signing key could mint any identity
  in the bank - and no other component issues one either. The Out-of-scope section's "beyond what is
  needed to obtain a token from the gateway" therefore describes a door that does not exist. So
  sign-in **takes** a token rather than a password, and a dev-only script mints one for the
  walkthrough against a keypair the gateway is pointed at. It is a test fixture, not a component, and
  the README says so in those words. Logged as a follow-up.

A third decision, taken because it shapes every screen: **an idempotency key belongs to an attempt,
not to a submission.** One key is minted when the customer confirms a transfer and is reused by every
retry of that request; it is replaced only when the request itself changes. That is the whole of
REQ-UI-002, and it is the one place where a plausible implementation - a fresh key per HTTP call -
moves money twice.

1. **Scaffold and toolchain.** Vite, React and TypeScript in strict mode with
   `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes` and `verbatimModuleSyntax`; Vitest,
   Testing Library and MSW for the suite; ESLint with `typescript-eslint`. Root `Makefile` gains
   `build-web`, `test-web` and `lint-web` beside the Go and Python targets, and `test-edge` picks up
   `test-web`. No component library and no state library: stratum 4's rule is that a dependency is
   justified rather than avoided, and neither clears the bar for four screens.
2. **`money.ts`.** Minor units and an ISO 4217 code, never separated, with the scale table resolving
   the decimal position at presentation only - the same shape as `batch/reporting/money.py` and the
   canonical model's rule in a third language. Its test parses the module's own source and fails on a
   float literal, a `/`, a `toFixed` or a `parseFloat`, because in JavaScript every number is the
   float the money rule forbids and only a source check can prove one never touched an amount.
3. **The API client and Problem Details.** A hand-written typed client over the operations the
   gateway routes, and an RFC 9457 reader. It must also understand the problem types the gateway
   itself emits - `unauthenticated`, `forbidden`, `rate-limited`, `no-route`, `upstream-timeout`,
   `upstream-unusable` - which F-34 records as being in no contract; this is the second consumer to
   need them. An unrecognised `type` degrades to the document's `title`, never to a bare status code.
4. **Session and sign-in.** The token and the account references live in memory for the life of the
   tab and nowhere else. A test asserts `localStorage` and `sessionStorage` are untouched and that
   the token never reaches `console` or an error surface - a bearer token in browser storage is
   readable by any script the page ever loads.
5. **Dashboard and balance.** One card per account showing **booked** and **available** as two
   labelled figures, never one, with the card stating why they differ when they do. A negative
   available figure is printed honestly rather than floored at zero, matching `Balance.available()`.
   REQ-UI-003 is a statement about what the screen may not imply, so the test asserts both numbers are
   present and distinguishable rather than that one of them renders.
6. **Statement.** A page at a time on the opaque `nextCursor`, oldest first, direction carrying the
   sign. Two tests: the page foots - opening balance plus every movement equals closing balance, which
   is the contract's own self-proving property - and the cursor is passed back byte-for-byte, never
   parsed, because a client that reads it has coupled itself to the server's sort key.
7. **Transfer - form, confirmation, result.** Validation against the contract's own constraints, a
   confirmation step that shows what will happen, and one idempotency key minted per attempt at
   confirmation. A retry after a failure sends the identical key and the identical body; editing the
   request mints a new one. Both directions are tested, because only the pair proves the rule.
8. **The pending state.** A submission whose response never arrived is `PENDING` - not success, not
   failure. The ledger allocates `transferRef` and `TransferRequest` carries no client reference, so a
   lost response leaves the client holding nothing but its key, and the only honest way to resolve it
   is to re-submit the identical request with the identical key and let the idempotency store replay
   the original outcome. "Check again" therefore re-submits rather than polling, and the screen says
   which of the two it is doing. Driven through MSW as a dropped connection, a timeout and a 500.
9. **Accessibility, documentation and traceability.** A keyboard path through the whole transfer
   journey, labelled inputs, and the result announced in a live region. The component README records
   both gaps above in plain words. Traceability rows for REQ-UI-001, 002 and 003.
10. **Verification and landing.** `make test-web`, `make build-web`, `make lint-web` and `make test`,
    then the live walkthrough against a running estate - transfer and reconcile, a hold making the two
    balances diverge on screen, and a lost response resolved to exactly one transfer. Actual output
    into the pull request.

## Definition of Done

- [ ] The transfer journey works end to end through the gateway.
- [ ] Retrying a failed submission does not move money twice.
- [ ] Booked and available balances are distinguishable in the UI.
- [ ] Problem Details errors render as meaningful messages.
- [ ] Type checking and linting pass with no suppressions.

## Verification

`npm test` and `npm run build`. Then a manual walkthrough against the running estate: make a
transfer, confirm the balance moves; simulate a lost response and retry, confirming money moves once;
place a hold and confirm booked and available diverge on screen.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-UI-001 Customers can transfer between accounts | transfer journey |
| REQ-UI-002 Retrying a transfer cannot move money twice | client-side idempotency key |
| REQ-UI-003 Available balance is never presented as spendable when held | balance view |
