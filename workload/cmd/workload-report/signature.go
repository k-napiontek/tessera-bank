package main

import (
	"fmt"
	"math"
	"strings"

	"github.com/k-napiontek/tessera-bank/workload/internal/manifest"
	"github.com/k-napiontek/tessera-bank/workload/internal/scenario"
	"github.com/k-napiontek/tessera-bank/workload/internal/slo"
)

// A signature is the half of a failure exercise that a write-up leaves out.
//
// It is easy to say that a condition made things worse, and it says nothing an operator can use.
// What is worth knowing is **which** graph moved, and - the load-bearing half - which one did not,
// because the graph that stays flat while customers cannot use the bank is the one somebody will be
// staring at during the incident. A scenario declares both lists up front, so what this section
// prints is an assertion that can be contradicted rather than a description that cannot.
//
// "Moved" is defined by the catalogue's own numbers and never by a tolerance invented here. An
// objective has moved when **its own line was crossed**: a ratio objective that was met in the
// baseline and is missed in this run, or a series objective whose closing value was inside its
// declared threshold in the baseline and outside it here. A threshold picked in this file would be
// a fourth place where the estate says what good looks like, which is what ADR 0012 exists to stop.

// standing is where an objective stood at the end of a run, in the only terms the catalogue gives.
func standing(reading slo.Reading) string {
	if reading.Computable {
		switch {
		case reading.Valid == 0:
			return "nothing happened"
		case reading.Met():
			return "met"
		default:
			return "missed"
		}
	}
	// A series objective yields two points rather than a ratio - the refusal F-78 records. Its own
	// threshold is the only line the catalogue draws for it, so the closing point against that line
	// is the most that can honestly be said, and it is enough to tell moved from flat.
	threshold := reading.Objective.SLI.Threshold
	if threshold == nil || math.IsNaN(reading.ObservedAfter) {
		return "unknown"
	}
	if within(reading.ObservedAfter, reading.Objective.SLI.Comparison, *threshold) {
		return "within threshold"
	}
	return "outside threshold"
}

func within(value float64, comparison string, threshold float64) bool {
	switch comparison {
	case "<=":
		return value <= threshold
	case "<":
		return value < threshold
	case ">=":
		return value >= threshold
	case ">":
		return value > threshold
	}
	return false
}

// A verdict on one declared objective.
type signature struct {
	objectiveID string
	component   string
	declared    string
	baseline    string
	run         string
	verdict     string
}

const (
	asDeclared   = "as declared"
	contradicted = "CONTRADICTED"
	inconclusive = "inconclusive"
)

// judge compares where an objective stood in the baseline with where it stood in this run.
func judge(declared, baseline, run string) string {
	if baseline == "unknown" || run == "unknown" {
		return inconclusive
	}
	moved := baseline != run
	if declared == "move" {
		if baseline == "missed" || baseline == "outside threshold" {
			// It was already over its line before the condition was applied, so this run cannot show
			// the condition moving it. Saying so beats crediting the injection with a failure the
			// fixture had all along - the mistake the WP-23 baseline's outbox objective would have
			// produced if anybody had diffed against it.
			return inconclusive
		}
		if moved {
			return asDeclared
		}
		return contradicted
	}
	if moved {
		return contradicted
	}
	return asDeclared
}

// signatures evaluates every objective the scenario names, in both captures.
func signatures(each scenario.Scenario, catalogue slo.Catalogue, run, baseline capture) []signature {
	declared := map[string]string{}
	order := make([]string, 0, len(each.MovesObjectives)+len(each.FlatObjectives))
	for _, id := range each.MovesObjectives {
		declared[id] = "move"
		order = append(order, id)
	}
	for _, id := range each.FlatObjectives {
		declared[id] = "flat"
		order = append(order, id)
	}

	found := map[string]signature{}
	for _, component := range catalogue.Components {
		for _, objective := range component.Objectives {
			kind, wanted := declared[objective.ObjectiveID]
			if !wanted {
				continue
			}
			runStanding := standing(slo.Evaluate(objective, run.before, run.after))
			baseStanding := standing(slo.Evaluate(objective, baseline.before, baseline.after))
			found[objective.ObjectiveID] = signature{
				objectiveID: objective.ObjectiveID,
				component:   component.ComponentID,
				declared:    kind,
				baseline:    baseStanding,
				run:         runStanding,
				verdict:     judge(kind, baseStanding, runStanding),
			}
		}
	}

	// In the order the scenario declared them, moves first. The catalogue's order is a different
	// statement and sorting would hide which half of the signature a line belongs to.
	out := make([]signature, 0, len(order))
	for _, id := range order {
		if entry, ok := found[id]; ok {
			out = append(out, entry)
		}
	}
	return out
}

// capture is one pair of scrapes.
type capture struct {
	before, after string
}

// renderSignature writes the section, or says plainly why there is none to write.
func renderSignature(out *strings.Builder, record manifest.Manifest, each scenario.Scenario,
	catalogue slo.Catalogue, run, baseline capture) {

	fmt.Fprintf(out, "\nSignature\n")
	fmt.Fprintf(out, "  Condition       %s - %s\n", each.ScenarioID, each.Title)
	fmt.Fprintf(out, "  Catalogue       %s\n", short(record.ScenarioDigest))
	fmt.Fprintf(out, "  Applied at      minute %d of the business day, held for %d\n",
		int(each.StartsAtMinute), each.HoldsForMinutes)

	if len(each.MovesObjectives) == 0 {
		fmt.Fprintf(out, "\n  This condition is declared to move nothing this estate has an "+
			"objective for.\n")
		for _, line := range wrap(each.MovesNothingBecause, 72) {
			fmt.Fprintf(out, "  %s\n", line)
		}
		fmt.Fprintf(out, "\n  What follows is therefore the whole of the assertion: these are the\n")
		fmt.Fprintf(out, "  objectives that must not have moved, and a run in which one of them did\n")
		fmt.Fprintf(out, "  is a finding about the objective rather than about the condition.\n")
	}

	fmt.Fprintf(out, "\n  %-32s  %-9s  %-18s  %-18s  %s\n",
		"objective", "declared", "baseline", "this run", "")
	for _, entry := range signatures(each, catalogue, run, baseline) {
		line := fmt.Sprintf("  %-32s  %-9s  %-18s  %-18s  %s",
			entry.objectiveID, entry.declared, entry.baseline, entry.run, entry.verdict)
		fmt.Fprintf(out, "%s\n", strings.TrimRight(line, " "))
	}

	fmt.Fprintf(out, "\n  An objective has moved when its own line was crossed, which is the "+
		"catalogue's\n")
	fmt.Fprintf(out, "  number rather than a tolerance chosen here. `inconclusive` means the "+
		"baseline was\n")
	fmt.Fprintf(out, "  already over that line, so this run cannot show the condition crossing it.\n")
}

// wrap breaks prose at a width, so a reason as long as a paragraph does not run off the page of a
// report that is diffed line by line.
func wrap(prose string, width int) []string {
	var lines []string
	var line strings.Builder
	for _, word := range strings.Fields(prose) {
		if line.Len() > 0 && line.Len()+1+len(word) > width {
			lines = append(lines, line.String())
			line.Reset()
		}
		if line.Len() > 0 {
			line.WriteString(" ")
		}
		line.WriteString(word)
	}
	if line.Len() > 0 {
		lines = append(lines, line.String())
	}
	return lines
}
