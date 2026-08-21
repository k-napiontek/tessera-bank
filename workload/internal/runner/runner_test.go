package runner_test

import (
	"context"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/arrivals"
	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
	"github.com/k-napiontek/tessera-bank/workload/internal/runner"
)

// A flat day of 86 400 events is one event a second of business time, which makes every count in
// these tests something a reader can check in their head.
func curve(t *testing.T) bankday.Curve {
	t.Helper()
	spec := bankday.CurveSpec{
		Weekday:         map[time.Weekday]float64{},
		DailyEventCount: 86_400,
		PaydayFactor:    1,
		MonthEndFactor:  1,
	}
	for hour := range spec.Diurnal {
		spec.Diurnal[hour] = 1
	}
	for day := time.Sunday; day <= time.Saturday; day++ {
		spec.Weekday[day] = 1
	}
	built, err := bankday.NewCurve(spec)
	if err != nil {
		t.Fatalf("NewCurve: %v", err)
	}
	return built
}

// spec is a population whose operations depend on nothing the run has to have posted first, so that
// what a given seed produces is fixed by the model alone.
func spec() population.Spec {
	return population.Spec{
		Size:                5_000,
		AccountsPerCustomer: 2,
		Cohorts: []population.Cohort{{
			ID: "retail", Share: 1, EventsPerCustomerPerDay: 10,
			Amount:     population.AmountSpec{MedianMinor: 10_000, Sigma: 1, MinMinor: 100, MaxMinor: 1_000_000},
			Currencies: []population.Weighted[money.Currency]{{Value: "PLN", Weight: 1}},
			Operations: []population.Weighted[string]{
				{Value: "getBalance", Weight: 0.6},
				{Value: "createTransfer", Weight: 0.4},
			},
		}},
	}
}

func settings(t *testing.T, sender runner.Sender, scale float64, compression int, from, to bankday.Minute) runner.Settings {
	t.Helper()
	people, err := population.New(spec())
	if err != nil {
		t.Fatalf("population.New: %v", err)
	}
	date, err := bankday.ParseDate("2026-08-31")
	if err != nil {
		t.Fatalf("ParseDate: %v", err)
	}
	process, err := arrivals.New(curve(t), date, scale)
	if err != nil {
		t.Fatalf("arrivals.New: %v", err)
	}
	return runner.Settings{
		People:      people,
		Process:     process,
		Date:        date,
		Seed:        42,
		Compression: compression,
		From:        from,
		To:          to,
		Held:        "PLN",
		Sender:      sender,
		Drain:       5 * time.Second,
	}
}

// recorder answers every request the same way and keeps what it was sent.
type recorder struct {
	delay time.Duration
	reply func(client.Request) client.Result

	mu       sync.Mutex
	requests []client.Request
	peak     int
	current  int
}

func (r *recorder) Send(_ context.Context, request client.Request, intended time.Time) client.Result {
	r.mu.Lock()
	r.current++
	if r.current > r.peak {
		r.peak = r.current
	}
	r.requests = append(r.requests, request)
	r.mu.Unlock()

	if r.delay > 0 {
		time.Sleep(r.delay)
	}

	r.mu.Lock()
	r.current--
	r.mu.Unlock()

	if r.reply != nil {
		return r.reply(request)
	}
	return client.Result{Outcome: client.Posted, Status: 201, Latency: time.Millisecond}
}

func (r *recorder) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.requests)
}

func (r *recorder) sent() []client.Request {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]client.Request(nil), r.requests...)
}

func TestASlowEstateDoesNotReduceTheOfferedRate(t *testing.T) {
	// The test this package exists for. Every response takes 50 milliseconds - twenty a second per
	// worker - and the schedule asks for seven hundred requests in one second. A closed model would
	// take the best part of a minute and would report an estate that was never offered the load.
	// An open model sends what the schedule says, when the schedule says, and the waiting shows up
	// in the latency where it belongs.
	slow := &recorder{delay: 50 * time.Millisecond}
	run := settings(t, slow, 0.2, 3600, 540, 600) // one business hour, compressed into one second

	summary, err := runner.Execute(context.Background(), run)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	if summary.Scheduled < 500 {
		t.Fatalf("the window scheduled only %d events - the test is not exercising anything", summary.Scheduled)
	}
	if summary.Sent != summary.Scheduled {
		t.Errorf("scheduled %d and sent %d", summary.Scheduled, summary.Sent)
	}
	// One second of schedule, plus the last request draining. A closed model with even fifty
	// workers could not do this in under half a minute.
	if summary.Elapsed() > 5*time.Second {
		t.Errorf("a one-second schedule took %s, which is a driver waiting for the estate", summary.Elapsed())
	}
	if slow.peak < 10 {
		t.Errorf("only %d requests were ever in flight at once, so they were being serialised", slow.peak)
	}
}

func TestNothingThrottlesWhenTheRunPassesWhatItExpected(t *testing.T) {
	// Expected is a number to report against, never a limit. A pool that blocked the scheduler here
	// would be a closed model wearing an open model's name, and it would be invisible in the output.
	slow := &recorder{delay: 30 * time.Millisecond}
	run := settings(t, slow, 0.2, 3600, 540, 600)
	run.Expected = 2

	summary, err := runner.Execute(context.Background(), run)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if summary.OverExpected == 0 {
		t.Error("the run never noticed it was above the level it was sized for")
	}
	if summary.Sent != summary.Scheduled {
		t.Errorf("sent %d of %d - something throttled", summary.Sent, summary.Scheduled)
	}
	if summary.PeakInFlight <= run.Expected {
		t.Errorf("peak in flight was %d, so the limit was enforced rather than reported", summary.PeakInFlight)
	}
}

func TestEveryScheduledEventIsAccountedFor(t *testing.T) {
	// The totals have to add up before they can be reconciled against the ledger's.
	fake := &recorder{}
	summary, err := runner.Execute(context.Background(), settings(t, fake, 0.05, 3600, 540, 600))
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	var unsent int64
	for _, count := range summary.Unsent {
		unsent += count
	}
	if summary.Sent+unsent != summary.Scheduled {
		t.Errorf("scheduled %d, sent %d, unsent %d", summary.Scheduled, summary.Sent, unsent)
	}

	var byOutcome int64
	for _, count := range summary.Outcomes {
		byOutcome += count
	}
	if byOutcome != summary.Sent {
		t.Errorf("%d requests are counted by outcome and %d were sent", byOutcome, summary.Sent)
	}
}

func TestOnlyTheEventsInTheWindowAreExecuted(t *testing.T) {
	// A window is how a run says "the lunch peak" rather than "a day". Sending either side of it
	// would put traffic in an hour the model says is quiet.
	fake := &recorder{}
	run := settings(t, fake, 0.05, 7200, 600, 660)

	summary, err := runner.Execute(context.Background(), run)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	// Counted independently, straight off the arrival process the run was given.
	var inWindow int64
	for event := range run.Process.Events(run.Seed) {
		if event.Minute >= run.To {
			break
		}
		if event.Minute >= run.From {
			inWindow++
		}
	}
	if summary.Scheduled != inWindow {
		t.Errorf("scheduled %d events and the window holds %d", summary.Scheduled, inWindow)
	}
}

func TestTheSameSeedAndModelProduceTheSameRequests(t *testing.T) {
	// REQ-PERF-002 at the far end: a manifest that promises reproducibility has to be promising
	// something the driver actually delivers.
	first := &recorder{}
	second := &recorder{}
	if _, err := runner.Execute(context.Background(), settings(t, first, 0.05, 7200, 540, 600)); err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if _, err := runner.Execute(context.Background(), settings(t, second, 0.05, 7200, 540, 600)); err != nil {
		t.Fatalf("Execute: %v", err)
	}

	if first.count() == 0 {
		t.Fatal("the run sent nothing")
	}
	if got, want := render(second.sent()), render(first.sent()); len(got) != len(want) {
		t.Fatalf("one run sent %d requests and the other %d", len(got), len(want))
	} else {
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("request %d differs:\n  %s\n  %s", i, want[i], got[i])
			}
		}
	}
}

// render puts the requests in a stable order and flattens each to what went on the wire. Sorted
// rather than compared in arrival order: the sends are concurrent by design, and which goroutine
// reaches the socket first is not something a schedule promises.
func render(requests []client.Request) []string {
	out := make([]string, 0, len(requests))
	for _, request := range requests {
		out = append(out, request.Method+" "+request.Path+" key="+request.Key+" body="+string(request.Body))
	}
	sort.Strings(out)
	return out
}

func TestADriverThatCannotKeepUpRecordsItsOwnLag(t *testing.T) {
	// The number that says the driver, not the bank, is the thing that fell behind. Without it a
	// run reports rising latency and nothing distinguishes an estate under strain from a load
	// generator that ran out of machine.
	fake := &recorder{}
	run := settings(t, fake, 0.05, 7200, 540, 600)
	run.Observer = slowObserver{}

	summary, err := runner.Execute(context.Background(), run)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if summary.MaxLag <= 0 {
		t.Error("a scheduler held up by its own bookkeeping recorded no lag at all")
	}
}

// slowObserver takes a millisecond to write down every change, which is enough to put a scheduler
// behind a schedule that releases an event every few hundred microseconds.
type slowObserver struct{}

func (slowObserver) Result(client.Request, client.Result) {}
func (slowObserver) Unsent(string, string)                {}
func (slowObserver) Lag(time.Duration)                    {}
func (slowObserver) InFlight(int)                         { time.Sleep(time.Millisecond) }

func TestARunWithNowhereToSendIsRefused(t *testing.T) {
	if _, err := runner.Execute(context.Background(), runner.Settings{}); err == nil {
		t.Error("a run with no sender started anyway")
	}
}

func TestACancelledRunStopsReleasingEvents(t *testing.T) {
	// Ctrl-C during a run, or a soak that has had enough. The summary still comes back, because a
	// run that was stopped early still says what it did.
	fake := &recorder{}
	ctx, cancel := context.WithCancel(context.Background())
	run := settings(t, fake, 0.2, 600, 540, 600) // ten seconds of schedule
	go func() {
		time.Sleep(200 * time.Millisecond)
		cancel()
	}()

	summary, err := runner.Execute(ctx, run)
	if err == nil {
		t.Error("a cancelled run reported no error")
	}
	if summary.Scheduled == 0 {
		t.Error("the summary of a cancelled run is empty")
	}
	if summary.Elapsed() > 5*time.Second {
		t.Errorf("the run took %s to notice it had been cancelled", summary.Elapsed())
	}
}
