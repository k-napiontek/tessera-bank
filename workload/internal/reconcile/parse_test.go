package reconcile_test

import (
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/reconcile"
)

// exposition is what services/ledger-api's /actuator/prometheus actually looks like, trailing comma
// in the label set and all. Transcribing a simplified version would test a parser against a format
// nothing emits.
const exposition = `# HELP jvm_threads_live_threads The current number of live threads
# TYPE jvm_threads_live_threads gauge
jvm_threads_live_threads 31.0
# HELP ledger_transfers_total Money-moving requests by operation and outcome
# TYPE ledger_transfers_total counter
ledger_transfers_total{operation="transfer",outcome="posted",} 412.0
ledger_transfers_total{operation="transfer",outcome="replayed",} 6.0
ledger_transfers_total{operation="transfer",outcome="rejected",} 19.0
ledger_transfers_total{operation="hold.place",outcome="posted",} 23.0
ledger_transfers_total{operation="reversal",outcome="failed",} 2.0
# HELP ledger_posting_latency_seconds
# TYPE ledger_posting_latency_seconds summary
ledger_posting_latency_seconds_count{operation="transfer",outcome="posted",} 412.0
ledger_posting_latency_seconds_sum{operation="transfer",outcome="posted",} 8.13
`

func TestTheLedgersOwnCounterIsReadAndTotalled(t *testing.T) {
	totals := reconcile.Transfers(exposition)

	if totals["posted"] != 435 {
		t.Errorf("posted totals %v, and the ledger counted 412 transfers and 23 holds", totals["posted"])
	}
	if totals["replayed"] != 6 || totals["rejected"] != 19 || totals["failed"] != 2 {
		t.Errorf("read %v", totals)
	}
}

func TestASummaryThatSharesTheNamePrefixIsNotCountedAsTheCounter(t *testing.T) {
	// ledger_posting_latency_seconds_count sits in the same exposition and starts with the same
	// words as nothing here - but ledger_transfers_total_created, which some clients emit, would.
	// A parser that matched on prefix alone would silently double a total.
	withCreated := exposition + "ledger_transfers_total_created{operation=\"transfer\",outcome=\"posted\",} 1.75e9\n"
	if totals := reconcile.Transfers(withCreated); totals["posted"] != 435 {
		t.Errorf("posted totals %v with a _created line present", totals["posted"])
	}
}

func TestADeltaIsWhatHappenedBetweenTwoScrapes(t *testing.T) {
	// The ledger has been up since long before this run. Its counters describe every request it has
	// ever served, and only the difference belongs to the window being reported.
	before := reconcile.ByOutcome{"posted": 400, "rejected": 10}
	after := reconcile.ByOutcome{"posted": 435, "rejected": 19, "failed": 2}

	moved, usable := reconcile.Delta(before, after)
	if !usable {
		t.Fatal("a counter that only went up was read as a restart")
	}
	if moved["posted"] != 35 || moved["rejected"] != 9 || moved["failed"] != 2 {
		t.Errorf("delta is %v", moved)
	}
}

func TestALedgerThatRestartedMidRunInvalidatesTheComparison(t *testing.T) {
	// Counters reset to zero. Subtracting the earlier scrape would report a negative number of
	// transfers, and a report that prints one has already lost the reader.
	before := reconcile.ByOutcome{"posted": 400}
	after := reconcile.ByOutcome{"posted": 12}

	if _, usable := reconcile.Delta(before, after); usable {
		t.Error("a counter that went backwards was treated as an ordinary delta")
	}
}

func TestThePostedColumnsHaveToAgree(t *testing.T) {
	driver := map[string]int64{"posted": 100, "replayed": 3, "rejected": 5}
	rows := reconcile.Compare(driver, reconcile.ByOutcome{"posted": 100, "replayed": 3, "rejected": 5})

	if !reconcile.Reconciled(rows) {
		t.Errorf("two identical accounts did not reconcile: %+v", rows)
	}

	off := reconcile.Compare(driver, reconcile.ByOutcome{"posted": 99, "replayed": 3, "rejected": 5})
	if reconcile.Reconciled(off) {
		t.Error("a transfer the driver posted and the ledger never saw reconciled anyway")
	}
}

func TestARefusalIsNotExpectedToReachTheLedger(t *testing.T) {
	// The gateway's rate limiter answered it. A reconciliation that demanded the ledger see them
	// would report a defect every time a control worked.
	rows := reconcile.Compare(map[string]int64{"posted": 10, "refused": 40}, reconcile.ByOutcome{"posted": 10})
	if !reconcile.Reconciled(rows) {
		t.Errorf("refusals broke the reconciliation: %+v", rows)
	}
	for _, row := range rows {
		if row.Outcome == "refused" && row.Note == "" {
			t.Error("the refused row carries no explanation of why it does not match")
		}
	}
}

func TestTheLedgersFailuresAreALowerBoundOnTheDriversUnknowns(t *testing.T) {
	// Every 500 the ledger recorded was answered to this driver, and some of the driver's unknowns
	// never reached the ledger at all. Fewer is consistent; more is not.
	fewer := reconcile.Compare(map[string]int64{"unknown": 9}, reconcile.ByOutcome{"failed": 4})
	if !reconcile.Reconciled(fewer) {
		t.Errorf("four ledger failures inside nine driver unknowns did not reconcile: %+v", fewer)
	}

	more := reconcile.Compare(map[string]int64{"unknown": 2}, reconcile.ByOutcome{"failed": 7})
	if reconcile.Reconciled(more) {
		t.Error("the ledger failed more requests than the driver ever sent, and that reconciled")
	}
}

func TestAnEmptyExpositionReadsAsNothingRatherThanFailing(t *testing.T) {
	// A ledger with no metrics endpoint reachable is a run that cannot be reconciled, and the
	// report has to say so rather than crash on the way to saying it.
	if totals := reconcile.Transfers(""); len(totals) != 0 {
		t.Errorf("read %v out of nothing", totals)
	}
	if samples := reconcile.Read("garbage\nnot an exposition\n", reconcile.Metric); len(samples) != 0 {
		t.Errorf("read %d samples out of garbage", len(samples))
	}
}

// A run the ledger answered entirely out of its idempotency store is not a measurement of the
// estate: nothing moved, so no outbox row was written, so nothing reached the broker and the
// scorer had nothing to consume. F-86 was three weeks of exactly that being read as a broken
// consumer, so the condition has a name and a test rather than a note in a log.
func TestReplayedEverythingSeparatesAReplayedRunFromAQuietOne(t *testing.T) {
	cases := []struct {
		name  string
		moved reconcile.ByOutcome
		want  bool
	}{
		{
			name:  "the ledger posted nothing and replayed everything",
			moved: reconcile.ByOutcome{"posted": 0, "replayed": 9080, "rejected": 43},
			want:  true,
		},
		{
			name:  "a run that posted is a run worth reading, replays and all",
			moved: reconcile.ByOutcome{"posted": 9080, "replayed": 12},
			want:  false,
		},
		{
			name:  "a run that moved no money at all is quiet, not replayed",
			moved: reconcile.ByOutcome{"posted": 0, "replayed": 0, "rejected": 0},
			want:  false,
		},
		{
			name:  "a window with no money-moving traffic in it says nothing either way",
			moved: reconcile.ByOutcome{},
			want:  false,
		},
		{
			name:  "rejections are not postings: they wrote no outbox row",
			moved: reconcile.ByOutcome{"posted": 0, "replayed": 1, "rejected": 500},
			want:  true,
		},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := reconcile.ReplayedEverything(testCase.moved); got != testCase.want {
				t.Errorf("ReplayedEverything(%v) = %v, want %v", testCase.moved, got, testCase.want)
			}
		})
	}
}
