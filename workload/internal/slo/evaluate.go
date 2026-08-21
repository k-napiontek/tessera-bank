package slo

import (
	"fmt"
	"math"
	"strings"

	"github.com/k-napiontek/tessera-bank/workload/internal/reconcile"
)

// This file names float64. Every figure in it is a ratio or a count of events, and none is money.

// Reading is what one objective came to over a run.
type Reading struct {
	Objective Objective

	// Computable is false when two snapshots cannot answer the question the objective asks, or when
	// the figures they gave are not consistent with each other. Why says which, and the figures
	// below are then not an answer.
	Computable bool
	Why        string

	Good  float64
	Valid float64

	// Observed is the SLI's value at each end of the run, for an objective that needs a series. It
	// is not an answer; it is the two points a reader has.
	ObservedBefore float64
	ObservedAfter  float64
}

// set records a figure, refusing one whose two halves cannot be describing the same events.
//
// More good events than valid ones is arithmetically impossible, so it never means the run went
// well: it means the numerator and the denominator came from series that do not correspond - a
// bucket bound that is not on this histogram, a label split against the wrong family, a metric
// renamed under one of them. Reporting it as a ratio above 1.0 and calling the objective met is the
// plausible wrong answer this estate keeps a trap list about, so it is a refusal instead.
func (r *Reading) set(good, valid float64) {
	if good > valid {
		r.Why = fmt.Sprintf(
			"%.0f good events out of %.0f valid ones, which is impossible - the two series being "+
				"divided do not describe the same events", good, valid)
		r.Good, r.Valid = good, valid
		return
	}
	r.Computable, r.Good, r.Valid = true, good, valid
}

// Achieved is the ratio the run produced. Only meaningful when Computable.
func (r Reading) Achieved() float64 {
	if r.Valid == 0 {
		return math.NaN()
	}
	return r.Good / r.Valid
}

// Met reports whether the run reached the objective. A run that produced no valid events has not
// missed anything, and saying it did would put a failure on the page for a quiet afternoon.
func (r Reading) Met() bool {
	return r.Computable && r.Valid > 0 && r.Achieved() >= r.Objective.Target
}

// BudgetSpent is the share of the error budget this run used, as a multiple: 1.0 is exactly the
// budget, and anything above it is an objective missed.
func (r Reading) BudgetSpent() float64 {
	if !r.Computable || r.Valid == 0 || r.Objective.ErrorBudget.Fraction == 0 {
		return math.NaN()
	}
	return (1 - r.Achieved()) / r.Objective.ErrorBudget.Fraction
}

// Evaluate works out one objective from the two scrapes that bracket a run.
//
// Counters are read as deltas. A counter's absolute value is whatever the process has done since it
// started, so reporting it would describe every run the ledger has ever served rather than this one -
// a plausible figure, in the right units, answering a question nobody asked.
func Evaluate(objective Objective, before, after string) Reading {
	reading := Reading{Objective: objective}
	sli := objective.SLI

	switch sli.ComputedFrom {
	case "counterLabels":
		reading.set(labelSplit(sli, before, after))

	case "histogramBucket":
		reading.set(bucketSplit(sli, before, after))

	case "counterPair":
		good := delta(sli.ExposedName, before, after, nil)
		valid := good
		for _, also := range sli.ValidAlsoCounts {
			valid += delta(also, before, after, nil)
		}
		reading.set(good, valid)

	case "seriesOverTime":
		// Deliberately not computed. A proportion of a window needs the window, and two samples are
		// two samples: producing a figure from them would be inventing one, which is the whole
		// reason the catalogue says how each objective is arrived at rather than leaving a tool to
		// decide. What a reader gets instead is the two points that actually exist.
		reading.Why = "a proportion of a window needs the window, and this run supplies two samples"
		reading.ObservedBefore = latest(sli.ExposedName, before)
		reading.ObservedAfter = latest(sli.ExposedName, after)

	default:
		reading.Why = fmt.Sprintf("the catalogue says %q and this tool does not implement it",
			sli.ComputedFrom)
	}
	return reading
}

// labelSplit divides one counter by the label the catalogue nominates.
func labelSplit(sli SLI, before, after string) (good, valid float64) {
	valid = delta(sli.ExposedName, before, after, nil)
	if sli.BadLabelPrefix != "" {
		bad := delta(sli.ExposedName, before, after, func(labels map[string]string) bool {
			return strings.HasPrefix(labels[sli.GoodLabel], sli.BadLabelPrefix)
		})
		return valid - bad, valid
	}
	wanted := map[string]bool{}
	for _, value := range sli.GoodValues {
		wanted[value] = true
	}
	good = delta(sli.ExposedName, before, after, func(labels map[string]string) bool {
		return wanted[labels[sli.GoodLabel]]
	})
	return good, valid
}

// bucketSplit divides the cumulative bucket at the threshold by the total count.
func bucketSplit(sli SLI, before, after string) (good, valid float64) {
	if sli.Threshold == nil {
		return 0, 0
	}
	bound := formatBound(*sli.Threshold)
	good = delta(sli.ExposedName+"_bucket", before, after, func(labels map[string]string) bool {
		return labels["le"] == bound
	})
	valid = delta(sli.ExposedName+"_count", before, after, nil)
	return good, valid
}

// formatBound renders a threshold the way Prometheus writes a bucket bound.
func formatBound(threshold float64) string {
	rendered := strings.TrimRight(strings.TrimRight(fmt.Sprintf("%f", threshold), "0"), ".")
	if rendered == "" || rendered == "-" {
		return "0"
	}
	return rendered
}

// delta sums the matching samples in each scrape and subtracts.
//
// A counter that went backwards means the process restarted mid-run, and the difference is then not
// a count of anything. Reported as zero rather than as a negative, which no counter can be.
func delta(metric, before, after string, matches func(map[string]string) bool) float64 {
	moved := total(after, metric, matches) - total(before, metric, matches)
	if moved < 0 {
		return 0
	}
	return moved
}

func total(exposition, metric string, matches func(map[string]string) bool) float64 {
	sum := 0.0
	for _, sample := range reconcile.Read(exposition, metric) {
		if matches != nil && !matches(sample.Labels) {
			continue
		}
		if math.IsNaN(sample.Value) {
			continue
		}
		sum += sample.Value
	}
	return sum
}

// latest is the highest value of a gauge in one scrape, which for a single-series gauge is its value.
func latest(metric, exposition string) float64 {
	best := math.NaN()
	for _, sample := range reconcile.Read(exposition, metric) {
		if math.IsNaN(sample.Value) {
			continue
		}
		if math.IsNaN(best) || sample.Value > best {
			best = sample.Value
		}
	}
	return best
}
