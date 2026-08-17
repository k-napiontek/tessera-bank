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

To be detailed before execution.

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
