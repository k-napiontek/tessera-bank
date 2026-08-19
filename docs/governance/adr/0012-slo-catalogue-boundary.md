# ADR 0012 - Declare the service level objective here, and page from somewhere else

**Status:** Accepted
**Date:** 2026-08-20
**Deciders:** Karol Napiontek

## Context

The workload strand (WP-20 to WP-25) puts the estate under load for the first time, and load without
a stated target is a number with nothing to compare it to. Four business metrics on the ledger, five
on the gateway, five on the fraud scorer, five on reporting - and nowhere a statement of what value
any of them ought to have. F-41 records the consequence precisely: the fraud score histogram "will
show a rule set drifting towards a threshold long before the outcomes change... but nothing watches
it, and there is no recorded normal to compare against."

So something has to say what good looks like. The question is where that something lives.

[ADR 0001](0001-source-only-repository.md) makes this repository application source and governance
configuration only. [`docs/runbooks/ledger-observability.md`](../../runbooks/ledger-observability.md)
has drawn a line since WP-09 - *"This is a map, not an alert policy: thresholds and dashboards live
in the platform repositories"* - and WP-09 and WP-17 both put dashboards and alert rules in their Out
of scope sections. That line was easy to hold while nothing here had an opinion about numbers. An SLO
is an opinion about numbers, and it is not obvious which side of the line it falls on.

Both readings are defensible. An objective is a promise about behaviour, which is a property of the
software and belongs beside it. An objective is also the thing an alert fires on, which is a property
of a deployment and belongs with the deployment.

## Decision

**The objective is declared here. The alert is configured elsewhere.**

Concretely, this repository holds, for every component that emits metrics:

- the **SLI**: which metric, with which tags, aggregated how;
- the **objective**: the target value and the measurement window;
- the **error budget** arithmetic that follows from the objective;
- a **recorded baseline**: what the estate actually did under a named workload model, with the
  manifest that produced it.

The platform repositories hold everything that turns those into an operational response: alerting
rules, burn-rate windows, notification routes, dashboards, recording rules and retention.

The split is not "documents here, YAML there". The test is whether the artefact would change if the
same code were deployed differently. An SLI definition would not - it is a statement about what the
software emits and means. A page threshold would: two instances of one service, one customer-facing
and one internal, deserve different pages from the same objective.

Two consequences of that test are worth naming because they look arbitrary otherwise:

- **`pg_stat_statements` is out.** It is exactly the signal an SLO for query cost would want, and
  enabling it requires `shared_preload_libraries`, which is server configuration. The catalogue names
  the signal and says where it comes from; it does not pretend this repository can switch it on.
- **The database metrics the ledger exports are in.** Pool utilisation, lock wait and table growth
  are things the ledger's own process can observe and publish, so they are the ledger's to emit -
  the same argument that put `ledger.outbox.lag` in WP-09 rather than in a platform repository.

## Consequences

**What becomes easier.** A platform repository can consume the catalogue instead of re-deriving it,
and two different deployments of this estate can hold the same objectives while paging differently.
A baseline committed beside the code means a regression is a diff rather than a memory. And the
strand gets a defensible answer to "why is there no dashboard in here", which is otherwise the first
question a reader asks.

**What becomes harder.** An objective and its alert now live in two repositories, and they can drift.
The failure is one-directional and worth stating: an alert firing on an objective this repository no
longer declares is noise, and an objective nobody alerts on is a claim nobody checks. The second is
the more likely and the more dangerous, because it looks like a control.

**What we are committed to.** Every future component that emits a metric owes a catalogue entry, and
the entry is part of its Definition of Done rather than a follow-up. A metric with no stated
objective is the state this ADR exists to end, and adding one more of them silently would undo it.

## Alternatives considered

**Put the alert rules here too.** Everything an SRE needs in one place, and immediately runnable. It
loses the boundary that defines this repository - and it makes the rules wrong for every deployment
except the one whoever wrote them had in mind. This is the same shape of argument as
[ADR 0006](0006-edge-rate-limit-is-per-instance.md): the convenient answer enforces a number that is
only correct in one topology, and being relied upon is what makes it dangerous.

**Put the SLO catalogue in the platform repositories, with the alerts.** Tidier, and it keeps this
repository free of numbers. It also means the objective is invented by whoever is deploying, rather
than by whoever built the thing and knows what it can promise - and it makes the objective invisible
to a reader of the code. WP-09 chose which outcomes were worth counting; the same reasoning decides
what those counts should look like when healthy, and separating the two puts the second decision in
the hands of someone without the first one's context.

**Declare no objectives at all and just report the numbers.** Honest, and useless. A run report
without a target is a report nobody can act on, and the baseline F-41 asks for is meaningless without
a statement of whether the baseline was any good.
