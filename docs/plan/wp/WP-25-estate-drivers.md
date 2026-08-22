# WP-25 - Estate-wide drivers

| | |
|---|---|
| **Ticket** | TB-1025 |
| **Branch** | `feat/TB-1025-estate-drivers` |
| **Stratum** | spans 0, 1 and 2 |
| **Depends on** | WP-21, WP-05, WP-10b, WP-11 |
| **Status** | `Not started` - detailed 2026-08-22 as two halves, 25a and 25b; no longer blocked, WP-10b and WP-11b landed 2026-08-20 |

## Objective

Drive the older strata from the same workload model that drives the modern one, so that "the estate
is under load" means the estate rather than the tier that happens to be easy to call.

The point of this repository is that a single customer transfer crosses four decades of technology.
A load exercise that stops at the REST API measures a quarter of it, and the quarter that was built
last year - while the interesting operational failures in a real bank happen where the eras meet: a
batch window that overruns, a SOAP endpoint whose thread pool is smaller than anyone remembers, a
movement file that arrives late and pushes reconciliation past the start of business.

## In scope

- Movement-file volume into the COBOL end-of-day cycle at realistic record counts, generated from
  the same WP-20 population as the online day.
- SOAP volume against `legacy/customer-master` on Tomcat 8.5.
- JMS volume through `integration/esb-adapter`.
- The batch window measured: how long the cycle takes at volume, and how that scales with the
  movement count.
- The online day and the overnight batch expressed as two phases of one model, so a compressed run
  shows the cut-off, the batch, and the morning reconciliation in sequence.

## Out of scope

- Any change to the pinned legacy stacks. Java 8, Boot 2.7, Tomcat 8.5 and COBOL-85 stay exactly
  where they are - `CLAUDE.md`'s legacy-strata rule admits no "while measuring it anyway".
- Fixing the generator's reject rate. See the constraint below.
- Tuning any legacy component. This package measures; a tuning change is its own decision with its
  own record.

## Constraints

- **No longer blocked.** This read "blocked until WP-10b and WP-11 are `Done`" until 2026-08-21,
  because when it was written `customer-master` had a complete WSDL and no `@WebService` anywhere and
  `integration/` held READMEs only, so two thirds of this package had nothing to call. Both landed on
  2026-08-20 - WP-10b as [#45](https://github.com/k-napiontek/tessera-bank/pull/45) `f43ce3f` and
  WP-11b as [#51](https://github.com/k-napiontek/tessera-bank/pull/51) `717153a` - and the line is
  corrected rather than left for a session to trip over. The package still carries **frame only** and
  the `work-package` skill halts on it until its task list is detailed.
- **F-18 must be closed first, or the stratum-0 measurement is a measurement of the reject path.**
  `build_movements` hard-codes `PLN` while `build_master` draws from five currencies, so 162 of 302
  movements reject on a full run. Loading that generator's output at volume exercises rejection
  handling and barely touches posting - a plausible-looking run that measures the wrong path, which
  is this repository's recurring failure mode.
- **The mainframe is driven by files, and files only.** There is no endpoint, no queue and no socket.
  A driver that reaches stratum 0 any other way has invented an integration the estate does not have.
- **`ORGANIZATION IS SEQUENTIAL`, and the generated file is COMP-3.** A high-volume generator that
  writes line sequential corrupts every packed field, and the file still opens and reads.
- The cycle refuses to apply the same movement file twice. A repeated volume run either uses a new
  file or passes `--rerun`, deliberately - doubling a day's postings is not a load test.
- No personal data. References only, as everywhere else.

## Tasks

> **2026-08-22: the two-phase run moved out of 25a into a third half, WP-25c.** 25a's tasks 1 to 4
> are built and verified; task 5 - the online day, the cut-off, the batch and the morning
> reconciliation in sequence - hit a dependency the detailing did not see. For `batch/recon` to
> compare the COBOL master against the ledger, both have to start from the same opening balances.
> The driver funds every account with `seeding.Opening`, twenty times the largest transfer the model
> can draw, and **`workload-dataset`'s NDJSON header does not carry that figure**, so the stratum-0
> writer cannot match it. Adding it changes the wire format `services/ledger-loader` also reads,
> which is WP-20's and WP-22's ground. Without it the reconciliation breaks on every account and
> measures nothing - a plausible-looking run describing nothing, which is this repository's named
> failure mode. So 25a lands the generator fix, the volume writer, the per-step timing and the
> window at three volumes, all verified, and the two-phase run becomes **WP-25c**, on the repository
> owner's instruction. **F-98** records the missing field.

Detailed 2026-08-22, in its own change rather than inside the branch that executes it - the same
reason the decision log records for WP-21 and WP-23. The package lands as **two halves on one
ticket**, WP-25a and WP-25b, each its own branch and pull request, tracked as two rows in
`STATUS.md` - and a third, WP-25c, split out during execution for the reason above. Detailed out, this package spans a generator fix, a stratum-0 file at volume, a batch
window timed at three record counts, a two-phase day, a SOAP driver against Tomcat 8.5, an event
driver through the Boot 2.7 adapter, and a fixture that has to boot Oracle and Tomcat for the first
time. The decision log's answer at this size, since WP-09, is to split the package in the plan rather
than the pull request.

The split runs where the running code already cuts. **25a is driven by files and nothing else** - a
generator, a movement file, `run-eod.sh`, `batch/recon`. **25b is driven by sockets** - a SOAP
endpoint on Tomcat 8.5 and an event consumer on Boot 2.7, both of which need containers the workload
fixture has never booted. Splitting anywhere else puts half of one transport in each pull request.

Five decisions are taken here rather than left for the executing session to improvise, and each one
changes what gets built.

- **F-18 is 25a's first task, not a precondition somebody else owns.** This package's own Constraints
  say the stratum-0 measurement is a measurement of the reject path until it is closed: `build_movements`
  hard-codes `PLN` on both legs while `build_master` draws from five currencies, so 162 of 302
  movements reject on a full run and multi-currency posting is never exercised at all. F-18 is filed
  against WP-05 and names `mainframe/data/generate.py` as WP-03's file, so **no package owns the
  fix** and it has stayed open through nineteen of them. Driving that generator at volume without
  closing it first would produce a plausible-looking run in which the majority of the work is
  rejection handling - this repository's recurring failure mode, and the reason the Constraint is
  worded the way it is.
- **The overnight window is two business dates, and `--window` goes on refusing to guess.** F-70
  records that `workload-plan --window overnight-batch` refuses a window wrapping midnight - 20:30 to
  05:00 is two spans of one business day - and points at `--from` and `--to`. That refusal is
  **correct and stays**: teaching one flag to mean two days is how a tool starts answering a question
  nobody asked it. 25a's two-phase run therefore spans **two business dates** explicitly, and its
  report names both, because the movement file the cycle consumes was produced by the previous day's
  online phase and that is a fact about the run rather than a formatting choice. F-70 closes as a
  decision rather than as a code change.
- **The transport into `esb-adapter` is Kafka, and this package will not invent a JMS one.** The
  In scope above says *"JMS volume through `integration/esb-adapter`"*, and there is no JMS anywhere
  in this estate: WP-11 built a `@KafkaListener`, the pom carries `spring-kafka` and neither
  `spring-integration` nor any JMS client, and `contracts/asyncapi/esb-adapter-events.yaml` is the
  interface. `integration/README.md` and `CLAUDE.md`'s stratum table both still say *"Spring
  Integration, JMS"*, which describes a component that was never built. Adding a broker to stratum 2
  to satisfy the wording would be **changing the estate to make it measurable**, which is exactly what
  WP-24's Constraint refused and F-85 recorded rather than worked around. 25b drives the transport
  the estate actually runs and the discrepancy is logged as **F-95** - a finding about the plan's own
  description, for the repository owner to settle, not for a work package to settle about itself.
- **The volume writer opens every account in the base currency and counts the substitutions, which
  is F-72's answer one stratum down.** WP-20's stream carries a currency on every *action* and none
  on any *account*, because the model draws a currency per transfer from a mix of up to five and
  gives each customer two accounts. Stratum 0 needs `ACCT-CURRENCY` on every `ACCTREC`, so the writer
  has to choose. Deriving an account's currency from the actions that touch it puts every other
  movement on the wrong currency and `ACCTPOST` rejects it `R003` - **F-18's failure mode reproduced
  through a different door**, at volume, in the run this package exists to measure. WP-21 met exactly
  this against the ledger and the estate already has the convention: `seeding.BaseCurrency` opens the
  population in the heaviest currency the model declares and
  `tessera_workload_currency_substituted_total` counts every transfer that went in it rather than in
  the one drawn - about 8% on the committed model. 25a reuses that rather than inventing a second
  answer, and **reports the count beside every figure**, so a reader knows what fraction of the
  drawn day the file represents. Multi-currency posting is exercised where the master genuinely
  draws five currencies - the committed fixture, after task 2 - rather than pretended at volume.
  Resolving it properly is still what F-72 says it is: accounts per currency in the population, which
  is a WP-20 model change.

- **WP-25 adds no scenario to `TB-SCENARIOS-V1`, so F-91 is not a precondition.** The catalogue-wide
  digest would invalidate all seven WP-24c captures if an eighth condition were added, and
  [ADR 0018](../../governance/adr/0018-the-migration-exercise-is-not-a-condition.md) already
  established the alternative: an exercise of its own, outside the catalogue. A batch window that
  overruns is worth declaring as a condition **after** F-91 is fixed, and it is logged as such rather
  than smuggled in here.

### WP-25a - the movement file at volume and the batch window

Branch `feat/TB-1025-batch-volume`. Five tasks, stratum 0 only. Task 5 as first detailed - the two
phases in sequence - became WP-25c during execution, per the note above.

1. Set 25a `In progress` and branch from up-to-date `main`.

2. **Close F-18.** A movement takes the currency of the account it lands on, and a transfer's two
   legs are drawn on accounts that share one - a cross-currency transfer is a different product and
   stratum 0 has no record for it. Movements are drawn against accounts whose status is `OPEN`. The
   **two deliberate reject fixtures stay exactly as they are**: the JPY unsupported-scale record and
   the unknown-account record are what WP-04 proves the mainframe's own validation with, and a
   generator with no rejects at all exercises the reject path less than the current one does. The
   test is the shape of the assertion: a full run's reject count equals the fixture count and nothing
   else, and every currency in `MASTER_CURRENCIES` appears among the posted movements. Test first,
   then the generator.

3. **The movement file at volume, from the WP-20 population.** `workload-dataset` already emits a
   drawn day as NDJSON over a pipe and `services/ledger-loader` already consumes it - WP-22's
   decision, so that neither side draws the bank's day twice. The stratum-0 writer consumes the
   **same** stream and writes `ACCTMAST.DAT` and `MOVEMENT.DAT`. It extends
   `mainframe/data/generate.py` rather than adding a writer under `workload/`: the COMP-3 encoder
   exists three times in this repository already (here, `esb-adapter`, `backoffice`) and a fourth in
   Go would be a fourth thing to keep in step with the copybooks, which are contracts. `ORGANIZATION
   IS SEQUENTIAL` and COMP-3 amounts, per the Constraints - a high-volume generator that writes line
   sequential corrupts every packed field and the file still opens and reads. Accounts open in the
   stream's base currency and every substitution is counted and reported, per the fifth decision
   above; `--out` keeps a volume run from overwriting the committed fixture.

4. **The cycle timed at three volumes, per step.** `run-eod.sh` already takes `--master`,
   `--movements`, `--work` and `--business-date`, so this task drives it and records rather than
   changing it. Per-step wall clock, not one total: STEP010 and STEP030 are sorts and STEP020 is the
   match-merge, and they do not scale the same way. The match-merge is the step the tier exists to
   demonstrate - it streams because the master does not fit in memory - so a total that hides it
   answers the wrong question. Three record counts, the scaling stated as what was measured rather
   than as a fitted curve, and the conditions named on the page beside every figure.

5. **The write-up, the matrix and the Verification below.** What the batch window costs at each
   volume, how it scales, and what closing F-18 changed about what the cycle actually exercises.

### WP-25c - the two phases of one day

Branch `feat/TB-1025-two-phase-day`. Split out of 25a on 2026-08-22, per the note at the top of this
section. Four tasks.

1. Set 25c `In progress` and branch from up-to-date `main`.

2. **The opening balance travels with the day.** `dataset.Header` gains what `seeding.Opening`
   computes - twenty times the largest transfer the model can draw, in the base currency - so that
   every consumer of the stream opens an account with the same figure the driver funds it with.
   `services/ledger-loader` reads the same header, so this is a change to shared ground and belongs
   in its own commit with a test on each side. **F-98**.

3. **The sequence.** Drive branch hours, stop at the `online-cut-off` instant the day contract
   declares at minute 1200, write the movement file from the same stream, run the cycle in
   `overnight-batch`, then `batch/recon` in `morning-reconciliation`. All three windows and the
   instant already exist in `contracts/workload/tessera-day-v1.json`; this sequences them and adds
   nothing to the contract. The run spans two business dates and the report names which date each
   phase belongs to, per the second decision above.

4. **What the reconciliation found**, written up as what it is. A break is not a failure of the job -
   `batch/recon`'s own exit code says so - and a run that reconciles exactly is a stronger claim than
   one that was made to. Whichever it is, the conditions go on the page beside it.

### WP-25b - SOAP and event volume against the older strata

Branch `feat/TB-1025-service-volume`. Five tasks, strata 1 and 2.

1. Set 25b `In progress` and branch from up-to-date `main`.

2. **Extend the fixture to boot stratum 1 and stratum 2.** `workload/scripts/estate-up.sh` boots
   PostgreSQL, Kafka, the ledger, `fraud-scoring` and the gateway. 25b adds Oracle, Tomcat 8.5 with
   the `customer-master` WAR, and `esb-adapter` as a Boot 2.7 process against the Kafka already
   there. Every one of those already starts inside a test suite in this repository - `oracle-free`
   and Cargo in `test-customer-master`, the adapter in `test-integration` - so this is assembling a
   fixture from parts that exist, not new infrastructure. **No component is changed and no pinned
   version moves**; anything that cannot be booted without changing one is recorded as a finding, the
   way F-85 recorded `SCN-CLOCK-SKEW`.

3. **SOAP volume against `customer-master`.** The three operations the WSDL declares - `GetAccount`,
   `GetAccountsByCustomer`, `NotifyTransferPosted` - driven from the same WP-20 population, at a rate
   the model produces rather than one invented here. What is recorded is what the Objective names:
   the connector's thread pool, the Oracle connection pool, and where the ceiling actually sits.
   WP-23's separation of the two lock timers is the shape to copy - one averaged "wait" that moves
   for two unrelated reasons answers neither question.

4. **Event volume through `esb-adapter`.** Kafka in, canonical XML by XSLT, SOAP to Tomcat, COMP-3
   movement record out - the whole four-era hop under sustained load, which `FourEraTransferIT`
   exercises exactly once. What is recorded is where the backlog forms and what the adapter does when
   the tier below it is slower than the tier above: consumer lag, the SOAP call's own latency, and
   whether anything reaches the dead-letter path. The Constraint that **nothing is written to the
   movement file unless the SOAP call succeeded** is WP-11b's, it is what makes the file trustworthy,
   and a load run is the first thing that will test it in anger.

5. **The write-up, the matrix, `REQ-PERF-008`, and the Verification below.**

## Definition of Done

The half that satisfies each box is named, because two pull requests cannot both tick all five.

- [x] One workload model produces both the online day and the overnight movement file. *(25a - the
      same WP-20 stream feeds `services/ledger-loader` and `generate.py --from-stream`)*
- [x] The end-of-day cycle runs at realistic volume and its duration is recorded against the record
      count. *(25a - three volumes to 2 429 346 movements, per step)*
- [ ] SOAP and event volume is driven, and each tier's behaviour under it is recorded. *(25b)*
- [ ] The online day, the cut-off, the batch and the morning reconciliation run in sequence. *(25c)*
- [x] The stratum-0 run posts the majority of what it is given, rather than rejecting it. *(25a -
      300 of 302 on the fixture, 2 429 346 of 2 429 346 at volume; F-18 closed)*
- [ ] No pinned version in strata 0, 1 or 2 was changed. *(all three halves; 25a holds - COBOL-85
      via `cobc -std=ibm`, and the one stratum-2 file it touched is a test literal)*

> **The third box read "SOAP and JMS volume" until 2026-08-22.** There is no JMS in this estate and
> there never was - WP-11 built a `@KafkaListener` against `contracts/asyncapi/esb-adapter-events.yaml`
> - so the box as written could not be ticked by measuring the estate, only by adding a broker to
> stratum 2. The wording is corrected to name the transport that exists and the discrepancy is
> recorded as **F-95** rather than silently renamed, because `integration/README.md` and `CLAUDE.md`'s
> stratum table still describe the same component the same wrong way. See the third decision under
> Tasks.

## Verification

```bash
bash contracts/validate.sh                       # the day model and the copybook contracts
make test-mainframe                              # the generator, the copybooks and the cycle  (25a)
make test-batch                                  # reporting and recon                          (25a)
make test-legacy                                 # customer-master and backoffice on real Tomcat (25b)
make test-integration                            # esb-adapter, real Kafka, really-deployed WAR  (25b)
make test                                        # every other tier still green
```

Then, and this is the half that cannot be automated: generate a day, run the online phase, cut off,
run the cycle at volume, then reconcile. Record the cycle duration against the movement count at
three volumes, **per step rather than as one total**, and state how it scales.

25b's suites need Docker, and between them they pull a ~2 GB Oracle image, a Tomcat 8.5 image and a
Kafka image. That is named here rather than discovered on the branch.

Real output into the pull request, never expected output. Both halves state the conditions every
figure was taken at - **a number without its conditions is a hunch wearing a decimal point.**

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-008 Every stratum is exercised at volume, not only the one that is easy to drive | the movement-file driver (25a) and the SOAP and Kafka drivers (25b), all three from one model |
