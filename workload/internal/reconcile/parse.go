// Package reconcile compares what the driver says it did against what the ledger says happened.
//
// A load run that only counts its own requests is a run that cannot be checked. The ledger keeps
// `ledger_transfers_total{operation,outcome}` at its own boundary, with the same distinction
// between posted, replayed and rejected that this driver makes - written by WP-09 for an operator,
// long before anything drove it. Two independent counts of the same events are what makes either
// worth reading.
//
// The two are not expected to agree in every column, and that is the interesting part. A refusal is
// counted here and never reaches the ledger at all, because the gateway stopped it. An unknown
// outcome may be a request the ledger applied and answered, an answer that was lost on the way
// back, or a request that never arrived - so the ledger's `failed` count is a lower bound on it and
// never an equal. Only posted, replayed and rejected have to match, and a difference in those is a
// finding rather than a rounding.
package reconcile

import (
	"strconv"
	"strings"
)

// Metric is the ledger's money-movement counter, as Micrometer renders it.
const Metric = "ledger_transfers_total"

// ByOutcome is a total per outcome.
type ByOutcome map[string]float64

// Transfers reads the ledger's counter out of a Prometheus exposition, summed over the operations
// it distinguishes.
//
// Summed, because the two sides name operations differently and neither naming is wrong: the ledger
// records what moved money - transfer, reversal, hold.place, hold.capture, hold.release - and the
// driver records the operationId it sent. Mapping one onto the other would be a table that has to
// be maintained in step with two components; totalling both sides is a comparison that stays true.
func Transfers(exposition string) ByOutcome {
	totals := ByOutcome{}
	for _, sample := range Read(exposition, Metric) {
		outcome := sample.Labels["outcome"]
		if outcome == "" {
			continue
		}
		totals[outcome] += sample.Value
	}
	return totals
}

// Sample is one line of an exposition.
type Sample struct {
	Labels map[string]string
	Value  float64
}

// Read parses every sample of one metric family.
//
// Written by hand for the same reason the exposition next door is: this module carries no
// dependencies. It handles what Micrometer actually emits, including the trailing comma inside the
// label set that some versions write and every parser is expected to tolerate.
func Read(exposition, name string) []Sample {
	var found []Sample
	for _, line := range strings.Split(exposition, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || !strings.HasPrefix(line, name) {
			continue
		}
		rest := line[len(name):]
		if rest == "" || (rest[0] != '{' && rest[0] != ' ') {
			// A different metric whose name merely starts with this one.
			continue
		}

		labels := map[string]string{}
		if strings.HasPrefix(rest, "{") {
			end := strings.Index(rest, "}")
			if end < 0 {
				continue
			}
			labels = parseLabels(rest[1:end])
			rest = rest[end+1:]
		}

		fields := strings.Fields(rest)
		if len(fields) == 0 {
			continue
		}
		value, err := strconv.ParseFloat(fields[0], 64)
		if err != nil {
			continue
		}
		found = append(found, Sample{Labels: labels, Value: value})
	}
	return found
}

func parseLabels(text string) map[string]string {
	labels := map[string]string{}
	for _, pair := range strings.Split(text, ",") {
		pair = strings.TrimSpace(pair)
		if pair == "" {
			continue
		}
		name, value, found := strings.Cut(pair, "=")
		if !found {
			continue
		}
		labels[strings.TrimSpace(name)] = strings.Trim(strings.TrimSpace(value), `"`)
	}
	return labels
}

// Delta is what happened between two scrapes. A counter that went backwards means the ledger
// restarted mid-run, which invalidates the comparison rather than producing a negative count.
func Delta(before, after ByOutcome) (ByOutcome, bool) {
	moved := ByOutcome{}
	restarted := false
	for outcome, end := range after {
		start := before[outcome]
		if end < start {
			restarted = true
			moved[outcome] = end
			continue
		}
		moved[outcome] = end - start
	}
	return moved, !restarted
}

// Row is one line of the reconciliation.
type Row struct {
	Outcome string
	// Driver is what this run counted.
	Driver int64
	// Ledger is what the ledger counted over the same window.
	Ledger int64
	// MustMatch reports whether a difference here is a defect. Refusals never reach the ledger and
	// an unknown outcome is a lower bound, so neither is expected to be equal.
	MustMatch bool
	// Note explains a row that is not expected to match.
	Note string
}

// Agrees reports whether this row is consistent with the ledger's own count.
func (r Row) Agrees() bool {
	if r.MustMatch {
		return r.Driver == r.Ledger
	}
	if r.Outcome == "unknown" {
		// The ledger's failures are the unknowns it saw. It cannot have seen more of them than the
		// driver recorded, because every one of the ledger's was answered to this driver.
		return r.Ledger <= r.Driver
	}
	return r.Ledger == 0
}

// Compare lines the two accounts up. driver is the driver's own count per outcome, over its
// money-moving operations only - the ledger counts nothing else.
func Compare(driver map[string]int64, ledger ByOutcome) []Row {
	rows := []Row{
		{Outcome: "posted", MustMatch: true},
		{Outcome: "replayed", MustMatch: true},
		{Outcome: "rejected", MustMatch: true},
		{Outcome: "refused", Note: "the gateway refused these; they never reached the ledger"},
		{Outcome: "unknown", Note: "the ledger's failures are a lower bound: an answer may have been lost on the way back"},
	}
	for index, row := range rows {
		rows[index].Driver = driver[row.Outcome]
		switch row.Outcome {
		case "unknown":
			rows[index].Ledger = int64(ledger["failed"])
		case "refused":
			rows[index].Ledger = 0
		default:
			rows[index].Ledger = int64(ledger[row.Outcome])
		}
	}
	return rows
}

// Reconciled reports whether every row is consistent.
func Reconciled(rows []Row) bool {
	for _, row := range rows {
		if !row.Agrees() {
			return false
		}
	}
	return true
}
