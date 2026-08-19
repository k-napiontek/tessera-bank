# WP-25 - Estate-wide drivers

| | |
|---|---|
| **Ticket** | TB-1025 |
| **Branch** | `feat/TB-1025-estate-drivers` |
| **Stratum** | spans 0, 1 and 2 |
| **Depends on** | WP-21, WP-05, WP-10b, WP-11 |
| **Status** | `Not started` - **blocked**, WP-10b and WP-11 are not started |

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

- **Blocked until WP-10b and WP-11 are `Done`.** `customer-master` has a complete WSDL and no
  `@WebService` anywhere, and `integration/` holds READMEs only. Two thirds of this package has
  nothing to call. Stated here rather than discovered by a session that picks it up.
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

To be detailed before execution.

## Definition of Done

- [ ] One workload model produces both the online day and the overnight movement file.
- [ ] The end-of-day cycle runs at realistic volume and its duration is recorded against the record
      count.
- [ ] SOAP and JMS volume is driven, and each tier's behaviour under it is recorded.
- [ ] The stratum-0 run posts the majority of what it is given, rather than rejecting it.
- [ ] No pinned version in strata 0, 1 or 2 was changed.

## Verification

Generate a day, run the online phase, cut off, run the cycle at volume, then reconcile. Record the
cycle duration against the movement count at three volumes and state how it scales.

## Traceability

| Requirement | Satisfied by |
|---|---|
| REQ-PERF-008 Every stratum is exercised at volume, not only the one that is easy to drive | the file, SOAP and JMS drivers, from one model |
