package slo_test

import (
	"math"
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/slo"
)

func threshold(v float64) *float64 { return &v }

// before and after bracket a run. The absolute values are deliberately not the run's figures: the
// ledger has been up for a while, and a report that printed what a counter says would describe every
// run the process has ever served.
const before = `
ledger_transfers_total{operation="transfer",outcome="posted",} 1000.0
ledger_transfers_total{operation="transfer",outcome="failed",} 5.0
ledger_transfers_total{operation="hold.release",outcome="replayed",} 40.0
tessera_gateway_requests_total{route="transfers",method="POST",status="201",} 900.0
tessera_gateway_requests_total{route="transfers",method="POST",status="503",} 10.0
tessera_gateway_requests_total{route="transfers",method="POST",status="429",} 20.0
ledger_posting_latency_seconds_bucket{operation="transfer",outcome="posted",le="0.5",} 800.0
ledger_posting_latency_seconds_bucket{operation="transfer",outcome="posted",le="+Inf",} 1000.0
ledger_posting_latency_seconds_count{operation="transfer",outcome="posted",} 1000.0
tessera_fraud_decisions_total{decision="allow",} 500.0
tessera_fraud_malformed_total 2.0
ledger_outbox_lag_seconds 1.0
`

const after = `
ledger_transfers_total{operation="transfer",outcome="posted",} 1090.0
ledger_transfers_total{operation="transfer",outcome="failed",} 6.0
ledger_transfers_total{operation="hold.release",outcome="replayed",} 43.0
tessera_gateway_requests_total{route="transfers",method="POST",status="201",} 980.0
tessera_gateway_requests_total{route="transfers",method="POST",status="503",} 12.0
tessera_gateway_requests_total{route="transfers",method="POST",status="429",} 28.0
ledger_posting_latency_seconds_bucket{operation="transfer",outcome="posted",le="0.5",} 880.0
ledger_posting_latency_seconds_bucket{operation="transfer",outcome="posted",le="+Inf",} 1090.0
ledger_posting_latency_seconds_count{operation="transfer",outcome="posted",} 1090.0
tessera_fraud_decisions_total{decision="allow",} 560.0
tessera_fraud_malformed_total 3.0
ledger_outbox_lag_seconds 7.0
`

func objective(sli slo.SLI, target float64, fraction float64) slo.Objective {
	return slo.Objective{
		ObjectiveID: "SLO-TEST",
		SLI:         sli,
		Target:      target,
		WindowDays:  30,
		ErrorBudget: slo.Budget{Fraction: fraction, MinutesPerWindow: fraction * 30 * 1440},
	}
}

func TestACounterIsReadAsTheRunRatherThanAsTheProcessLifetime(t *testing.T) {
	// The ledger posted 90 and failed 1 during this run. Its counters say 1090 and 6, which is what
	// the process has done since it started - a figure in the right units describing the wrong thing.
	reading := slo.Evaluate(objective(slo.SLI{
		Kind: "eventRatio", ExposedName: "ledger_transfers_total",
		Tags: []string{"operation", "outcome"}, ComputedFrom: "counterLabels",
		GoodLabel: "outcome", GoodValues: []string{"posted", "replayed", "rejected"},
	}, 0.999, 0.001), before, after)

	if !reading.Computable {
		t.Fatalf("a label split should be computable: %s", reading.Why)
	}
	// posted 90 + replayed 3 = 93 good, out of 94 valid.
	if reading.Good != 93 || reading.Valid != 94 {
		t.Fatalf("good %v valid %v, want 93 of 94", reading.Good, reading.Valid)
	}
	if reading.Met() {
		t.Errorf("93/94 is %.4f and the target is 0.999", reading.Achieved())
	}
}

func TestAnOpenEndedLabelIsSplitByWhatIsBadRatherThanByListingWhatIsGood(t *testing.T) {
	// Statuses cannot be enumerated. Listing the good ones would silently move every status nobody
	// thought of onto the bad side, and a 429 - a control working - is the one that matters here.
	reading := slo.Evaluate(objective(slo.SLI{
		Kind: "eventRatio", ExposedName: "tessera_gateway_requests_total",
		Tags: []string{"route", "method", "status"}, ComputedFrom: "counterLabels",
		GoodLabel: "status", BadLabelPrefix: "5",
	}, 0.999, 0.001), before, after)

	// 80 created + 8 refused = 88 good, 2 failed, 90 valid.
	if reading.Good != 88 || reading.Valid != 90 {
		t.Fatalf("good %v valid %v, want 88 of 90", reading.Good, reading.Valid)
	}
}

func TestALatencyObjectiveComesFromTheBucketAtItsOwnThreshold(t *testing.T) {
	reading := slo.Evaluate(objective(slo.SLI{
		Kind: "eventRatio", ExposedName: "ledger_posting_latency_seconds",
		Tags: []string{}, Threshold: threshold(0.5), Comparison: "<=",
		ComputedFrom: "histogramBucket",
	}, 0.99, 0.01), before, after)

	// 80 of the run's 90 postings were inside half a second.
	if reading.Good != 80 || reading.Valid != 90 {
		t.Fatalf("good %v valid %v, want 80 of 90", reading.Good, reading.Valid)
	}
	if spent := reading.BudgetSpent(); math.Abs(spent-11.111) > 0.01 {
		t.Errorf("budget spent %.3f, want about 11.1 times over", spent)
	}
}

func TestTheDenominatorIncludesTheEventsThatNeverBecameTheThingCounted(t *testing.T) {
	// 60 decisions and 1 message that could not be read. Leaving the unreadable one out would make
	// the SLI exactly 1.0 - the flattering answer, produced by measuring only what worked.
	reading := slo.Evaluate(objective(slo.SLI{
		Kind: "eventRatio", ExposedName: "tessera_fraud_decisions_total",
		Tags: []string{"decision"}, ComputedFrom: "counterPair",
		ValidAlsoCounts: []string{"tessera_fraud_malformed_total"},
	}, 0.9999, 0.0001), before, after)

	if reading.Good != 60 || reading.Valid != 61 {
		t.Fatalf("good %v valid %v, want 60 of 61", reading.Good, reading.Valid)
	}
}

func TestAnObjectiveOverAWindowIsNotAnsweredFromTwoSamples(t *testing.T) {
	reading := slo.Evaluate(objective(slo.SLI{
		Kind: "timeRatio", ExposedName: "ledger_outbox_lag_seconds",
		Tags: []string{}, Threshold: threshold(60), Comparison: "<=",
		ComputedFrom: "seriesOverTime",
	}, 0.995, 0.005), before, after)

	if reading.Computable {
		t.Fatal("two samples cannot give the proportion of a window, and saying so is the point")
	}
	if reading.Why == "" {
		t.Error("a reader is told nothing about why there is no figure")
	}
	if reading.ObservedBefore != 1 || reading.ObservedAfter != 7 {
		t.Errorf("observed %v then %v, want the two points that do exist",
			reading.ObservedBefore, reading.ObservedAfter)
	}
	if reading.Met() {
		t.Error("an objective with no figure has not been met")
	}
}

func TestACounterThatWentBackwardsIsNotANegativeCount(t *testing.T) {
	// The process restarted mid-run. The difference is not a count of anything, and a negative one
	// would make a ratio that reads as a catastrophe or as better than perfect.
	reading := slo.Evaluate(objective(slo.SLI{
		Kind: "eventRatio", ExposedName: "ledger_transfers_total",
		Tags: []string{"operation", "outcome"}, ComputedFrom: "counterLabels",
		GoodLabel: "outcome", GoodValues: []string{"posted"},
	}, 0.999, 0.001), after, before)

	if reading.Good < 0 || reading.Valid < 0 {
		t.Fatalf("good %v valid %v: a counter cannot go backwards", reading.Good, reading.Valid)
	}
}

func TestACatalogueThisToolDoesNotUnderstandIsRefused(t *testing.T) {
	if _, err := slo.Decode([]byte(`{"catalogueId":"TB-SLO-CATALOGUE-V2","components":[{}]}`)); err == nil {
		t.Error("a catalogue announcing a format this tool cannot read was accepted")
	}
	if _, err := slo.Decode([]byte(`{"catalogueId":"TB-SLO-CATALOGUE-V1","components":[],"burnRate":2}`)); err == nil {
		t.Error("a catalogue carrying a field this tool ignores was accepted, and the report would " +
			"not have said so")
	}
}

func TestMoreGoodEventsThanValidOnesIsRefusedRatherThanCalledSuccess(t *testing.T) {
	// A bucket bound that is not on this histogram, or a label split against the wrong family,
	// produces a numerator that does not belong to its denominator. The arithmetic then says the
	// run was better than perfect, which is exactly the sort of plausible wrong answer that gets
	// believed - so it is a refusal rather than a ratio above 1.0.
	const impossibleAfter = `
ledger_posting_latency_seconds_bucket{operation="transfer",outcome="posted",le="0.5",} 1200.0
ledger_posting_latency_seconds_count{operation="transfer",outcome="posted",} 1090.0
`
	reading := slo.Evaluate(objective(slo.SLI{
		Kind: "eventRatio", ExposedName: "ledger_posting_latency_seconds",
		Tags: []string{}, Threshold: threshold(0.5), Comparison: "<=",
		ComputedFrom: "histogramBucket",
	}, 0.99, 0.01), before, impossibleAfter)

	if reading.Computable {
		t.Fatalf("400 good of 90 valid was accepted as a figure: %.5f", reading.Achieved())
	}
	if reading.Met() {
		t.Error("an objective was reported as met on arithmetic that cannot be true")
	}
	if reading.Why == "" {
		t.Error("a reader is told nothing about why the figure was refused")
	}
}
