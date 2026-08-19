# WP-19 - web-banking design system

| | |
|---|---|
| **Ticket** | TB-1019 |
| **Branch** | `feat/TB-1019-web-design-system` |
| **Stratum** | 4 - TypeScript + React, ~2025 |
| **Depends on** | WP-14 |
| **Status** | `Done` |

## Objective

Give `edge/web-banking` the visual language of a retail bank, mobile first and responsive to the
desktop, without changing a line of what it does.

WP-14 built the journey and said so in its own stylesheet: *"A restrained sheet, and deliberately
so... Everything else is ordinary."* That was the right call at the time - the package's risk was in
idempotency and in money arithmetic, not in appearance. The result is 334 lines of CSS, nine tokens,
one media query, no navigation structure, no mark and no favicon. On a phone it is a document with
a centred column, not an application.

This package supplies the missing layer: a token system, a shell that reshapes across three
breakpoints, and a palette taken from PKO Bank Polski's own properties, since that is the interface
a Polish retail customer recognises. **All 123 tests WP-14 left behind stay green, unchanged.** A redesign that has to
change a behavioural test has changed behaviour, and that is out of scope by definition.

## In scope

- A design token system: colour, spacing, radius, type scale, in one file, checked by a test.
- One self-hosted variable typeface, served same-origin.
- An application shell: sticky app bar, a bottom tab bar under 1024px, a persistent left navigation
  rail at and above it.
- Restyled screens - sign-in, dashboard, statement, transfer - and the markup changes the styling
  needs, and no others.
- A wordmark and a favicon. The application currently has neither.
- A contrast test over the token file.

## Out of scope

- **Any behaviour change.** No new API call, no new route, no new screen, no change to `money.ts`,
  `ledger.ts`, `api/` or `session/`.
- A dark theme. The existing `prefers-color-scheme: dark` block is removed rather than extended -
  PKO's own interface is light only, and a half-maintained second palette is worse than one.
- Localisation. The interface stays English, per CLAUDE.md.
- Amount and date formatting. `toPlainString` is load-bearing and its output is asserted in tests.
- New requirements. This package introduces no `REQ-*` id; it re-verifies three.

## Constraints

- **No new runtime dependency.** WP-14 task 1 forbids a component library and a state library, and
  a CSS framework is the same argument in a different coat. Hand-written CSS, as now. The typeface
  is an asset, not a package, and it is committed rather than fetched.
- **No third-party origin.** A page that holds a bearer token must not ask `fonts.gstatic.com` for
  anything. The font is served from the application's own origin.
- **REQ-UI-003 keeps its words.** Two labelled figures on every card, always; the held amount stated
  in a sentence; a negative available balance printed honestly rather than floored at zero. Anything
  this package adds is an addition to that, never a replacement for it.
- **The test suite is the contract.** Roles, accessible names and heading text are what the 123
  existing tests query. Restyle freely; do not rename. The keyboard test in
  `accessibility.test.tsx` asserts a tab order - a focusable element inserted ahead of the transfer
  form breaks it, and that is a defect rather than a test to update.
- **Every colour pair is verified, not eyeballed.** WCAG 2.2 AA: 4.5:1 for text, 3:1 for a UI
  boundary. `#DB912C` is PKO's call-to-action amber and it reaches 2.5:1 on white, so it may fill a
  shape and may not carry a word.
- No personal data. The token stays out of storage, out of the DOM after sign-in and out of the
  console.

## Tasks

The palette is read off PKO's live properties rather than recalled: `#003574` is the navy that
`ipko.pl` uses for its primary action and its brand type, `#CA171D` its red, `#DB912C` the amber
`pkobp.pl` puts on a call to action, `#2E7D49` its green, over greys `#F2F2F2`/`#E5E5E5`/`#636363`.
The reference is **iPKO**, the transactional interface, not the marketing site: these four screens
are transactional, and navy is what a customer sees once they are through the door. Nothing of PKO's
mark, name or wordmark is reproduced - the palette is the reference, the identity is Tessera's.

1. **The contrast test, first.** `styles/contrast.test.ts` parses `tokens.css` for every
   `--name: #hex`, computes WCAG relative luminance, and asserts a declared list of
   foreground-over-background pairs clears its threshold. Same control as `money.source.test.ts`,
   which parses `money.ts` and fails on a division: the mistake a stylesheet makes is not a crash,
   it is a plausible-looking colour that a fifth of readers cannot read. Pure arithmetic, no
   dependency.
2. **Tokens.** `styles/tokens.css` - the palette above, a 4px spacing ramp, three radii, an eight-step
   type scale. One file, one `:root`, no second palette.
3. **The typeface.** Figtree Variable, OFL-1.1, committed as one `.woff2` with its licence beside it
   and declared in `base.css` with a system fallback stack. It is chosen for having **tabular
   figures**: `.amount { font-variant-numeric: tabular-nums }` is load-bearing - digits that line up
   down a column make a misread figure harder to produce - so a face without `tnum` disqualifies
   itself. Verified before it is committed. OFL-1.1 is not on `dependency-policy.md`'s approved list
   and is added there, because this is the first asset in the repository that needs it.
4. **The shell.** `shell/AppShell.tsx` lifts the masthead, the navigation and `<main>` out of
   `App.tsx`. **One `<nav>` at every width**, repositioned by media query: a fixed bottom tab bar
   under 1024px, a sticky 240px rail above it. Two navigations would mean two tab orders and every
   link announced twice. The bottom bar carries `env(safe-area-inset-bottom)` and `<main>` reserves
   the height, so the last row of a long statement is not trapped under it.
5. **The wordmark.** Inline SVG, no asset file: four tesserae forming a square with one offset. A
   tessera is the tile a mosaic is made of, which is what this estate is - five strata, thirty years
   apart, one system. Monochrome, so it inverts onto navy without a second file. The same shape
   becomes `public/favicon.svg`.
6. **The balance meter.** REQ-UI-003 is a statement about what a screen may not imply, and today the
   card discharges it in a sentence. `BalanceMeter` also shows it: a two-segment bar, available
   against held, under the hero figure. It renders nothing when the two balances agree, it never
   replaces the sentence, and it carries its own accessible name stating the held amount - a bar
   that only a sighted user can read would discharge the requirement for some readers and not
   others. This is the one ornament on the page.
7. **Dashboard.** Each card leads with its available balance, large, and carries booked beneath it,
   quieter and never absent. **No aggregate hero across accounts**, which the first sketch had:
   accounts are fetched independently and may be in different currencies, and adding 100 PLN to
   100 EUR produces a figure no auditor would accept - the same call WP-05 made when it refused a
   cross-currency grand total on the end-of-day report. Cards keep `role="article"` and their
   `aria-label`; the account reference stays monospace, because sixteen characters are checked one
   at a time and a proportional face makes that harder.
8. **Statement.** One `<table>` at every width, with all four columns and all four headers. The
   first sketch folded the side into the effect cell on a phone, and that was dropped: every one of
   the four is a fact a customer may need to quote, and the alternative - turning rows into stacked
   `div`s - costs the table role in the accessibility tree, which is a real loss to pay for a
   cosmetic gain. So the table keeps its semantics and is given a labelled, focusable scroll region
   instead. Measured: the table needs 470px, a 390px phone offers 358px, so the region scrolls and
   the page does not. The `visually-hidden` caption stays.
9. **Transfer.** The five-stage state machine is already correct; this makes it legible. A three-step
   indicator - Details, Confirm, Result - marks the current stage, and the pending panel keeps its
   amber left border and its plain warning not to enter the transfer again. No focusable element is
   added ahead of the form.
10. **Sign-in.** A centred card on a navy field, with the wordmark above it. The explanation of why
    this bank asks for a token rather than a password stays exactly as written - it is the first
    thing a reader learns about the estate, and it is true.
11. **Verification and landing.** `make test-web`, `make build-web`, `make lint-web`, then the
    application driven in a browser at three widths with every screen and every transfer state seen
    and screenshotted. Actual output into the pull request.

## Definition of Done

- [x] Every test that passed before this package passes after it, unchanged. 123 on `main`, 169 on
      this branch, no test file edited that was not added here.
- [x] The contrast test passes, and fails when a token is darkened past its threshold. Demonstrated
      both ways: `--ink-muted` lightened to `#949494` failed three pairs at 3.03:1, 2.85:1 and
      3.20:1, and an unpaired `--teal-500` failed the accounting test.
- [x] The layout reshapes at 640px and 1024px, with the bottom tab bar and the rail never both
      present, and no page-level horizontal scroll. Driven in Chrome at its narrowest window (591px
      viewport, inside the mobile branch) and at 1440px. The one element wider than a 390px phone
      is the movements table, which is why it has a scroll region: 470px needed, 358px offered,
      region scrolls, `document.documentElement` does not.
- [x] Booked and available remain two labelled figures with the held amount in words; the meter is
      present only when they differ and carries an accessible name stating the held amount.
- [x] The transfer journey still completes from the keyboard alone - `accessibility.test.tsx`
      unchanged and passing, and no focusable element was added ahead of the form.
- [x] No new runtime dependency, and no request to a third-party origin. `package.json` is
      byte-identical to `main`; the typeface is two `woff2` files served from this origin.
- [x] Type checking and linting pass with no new suppressions.

## Verification

```bash
make test-web     # the regression gate: 123 existing tests plus this package's 46
make build-web    # tsc --noEmit strict, then vite build
make lint-web     # eslint --max-warnings 0
```

Then in a browser, because a design change that is only tested is not verified:

```bash
npm --prefix edge/web-banking run dev
```

Sign-in, dashboard with and without a held amount, statement, and all five transfer states, at
390x844, 768x1024 and 1440x900.

## Traceability

This package introduces no requirement. It re-verifies three, and the matrix records the evidence
moving rather than the requirement changing.

| Requirement | Still satisfied by |
|---|---|
| REQ-UI-001 Customers can transfer between accounts | transfer journey, unchanged in behaviour |
| REQ-UI-002 Retrying a transfer cannot move money twice | client-side idempotency key, untouched |
| REQ-UI-003 Available balance is never presented as spendable when held | two labelled figures and the sentence, now also the balance meter |
