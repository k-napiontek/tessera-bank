// Package runner executes a schedule against a running estate.
//
// **The model is open** - [ADR 0016]. Every send time is fixed before the run starts and nothing
// that happens to a request can move another one. A closed model - N workers each waiting for a
// reply before sending again - throttles itself precisely when the system slows down, so offered
// load falls exactly when the interesting thing is happening and the latency comes out flattering.
// That is coordinated omission, and the output looks entirely plausible.
//
// The cost ADR 0016 names is paid here rather than dodged: an open model's concurrency is unbounded
// in principle. This scheduler never waits for a worker. It records how many requests are in flight
// and counts the moments that number passes the level a run declared it expected, which is a fact
// about the run that a reader can weigh - and it is the opposite of a pool that quietly turns the
// model closed by blocking the loop that hands out work.
//
// [ADR 0016]: ../../../docs/governance/adr/0016-the-workload-model-is-open.md
package runner

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/arrivals"
	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
)

// Sender is the part of client.Sender a run needs.
type Sender interface {
	Send(ctx context.Context, request client.Request, intended time.Time) client.Result
}

// Observer is told about the run as it happens. internal/metrics implements it; a run with no
// observer still produces its summary, because the summary is what the report is written from.
type Observer interface {
	// Result reports one completed request.
	Result(request client.Request, result client.Result)
	// Unsent reports a scheduled event that produced no request, and why.
	Unsent(operation, reason string)
	// Lag reports how far behind its own schedule the scheduler was when it released an event.
	Lag(behind time.Duration)
	// InFlight reports the number of requests outstanding, after a change.
	InFlight(current int)
}

// Settings are one run.
type Settings struct {
	People  population.Population
	Process arrivals.Process
	Date    bankday.Date
	Seed    uint64
	// Compression speeds the day up: 72 runs twenty-four hours in twenty minutes. It divides every
	// intended send time and multiplies every rate, which is why the manifest records it beside the
	// scale rather than folding the two together.
	Compression int
	From, To    bankday.Minute
	// Held is the currency the estate's accounts are open in.
	Held money.Currency

	Sender   Sender
	Observer Observer

	// Expected is the number of requests a run expects to have in flight at once. Passing it is not
	// an error and nothing is throttled; it is counted, because a run that spent its time above the
	// level it was sized for is a run whose latency figures deserve to be read with that in mind.
	Expected int
	// Drain bounds how long the run waits for the last requests after the schedule is exhausted.
	Drain time.Duration

	// Now and Sleep exist so a test can watch a run without waiting for one. Both default to the
	// real thing.
	Now   func() time.Time
	Sleep func(time.Duration)
}

// Summary is what the run did. It is the driver's own account, and reconciling it against the
// ledger's `ledger_transfers_total` for the same window is what proves the two agree.
type Summary struct {
	Scheduled   int64
	Sent        int64
	Unsent      map[string]int64
	Outcomes    map[client.Outcome]int64
	ByOperation map[string]map[client.Outcome]int64
	Substituted int64
	Retried     int64
	Refused     int64

	MaxLag       time.Duration
	TotalLag     time.Duration
	PeakInFlight int
	OverExpected int64

	LatencyCount int64
	LatencySum   time.Duration
	LatencyMax   time.Duration

	Started  time.Time
	Finished time.Time
}

// Elapsed is the wall-clock length of the run.
func (s Summary) Elapsed() time.Duration { return s.Finished.Sub(s.Started) }

// MeanLatency is the average time from a request's intended send time to its answer.
func (s Summary) MeanLatency() time.Duration {
	if s.LatencyCount == 0 {
		return 0
	}
	return s.LatencySum / time.Duration(s.LatencyCount)
}

// Reasons an event produced no request.
const (
	// ReasonNoReference is "read the transfer I just made", scheduled before anything had been
	// made. Sending an invented reference instead would manufacture 404s no client would produce.
	ReasonNoReference = "no-reference-yet"
	// ReasonUnbuildable is an action this driver has no request shape for. Unreachable from a model
	// the contract validated, and counted rather than dropped.
	ReasonUnbuildable = "unbuildable"
)

// Execute runs the schedule. It returns when every scheduled event has been released and the last
// requests have drained, or when the context is cancelled.
func Execute(ctx context.Context, settings Settings) (Summary, error) {
	if settings.Sender == nil {
		return Summary{}, errors.New("runner: a run needs somewhere to send")
	}
	if settings.Compression < 1 {
		settings.Compression = 1
	}
	if settings.Drain <= 0 {
		settings.Drain = 30 * time.Second
	}
	if settings.Now == nil {
		settings.Now = time.Now
	}
	if settings.Sleep == nil {
		settings.Sleep = func(d time.Duration) { time.Sleep(d) }
	}
	if settings.Observer == nil {
		settings.Observer = silent{}
	}

	run := &state{
		settings: settings,
		known:    newMemory(),
		summary: Summary{
			Unsent:      map[string]int64{},
			Outcomes:    map[client.Outcome]int64{},
			ByOperation: map[string]map[client.Outcome]int64{},
			Started:     settings.Now(),
		},
	}

	windowStart := time.Duration(settings.From) * time.Minute
	compression := time.Duration(settings.Compression)

	for event := range settings.Process.Events(settings.Seed) {
		if event.Minute < settings.From {
			continue
		}
		if event.Minute >= settings.To {
			break
		}
		if ctx.Err() != nil {
			break
		}

		due := run.summary.Started.Add((event.At - windowStart) / compression)
		if wait := due.Sub(settings.Now()); wait > 0 {
			settings.Sleep(wait)
		} else if wait < 0 {
			// The scheduler itself is late. Recorded rather than absorbed: a run whose lag is
			// climbing is a run whose numbers describe the driver, and no amount of care inside the
			// request path makes up for a schedule that was never offered.
			run.behind(-wait)
		}

		run.summary.Scheduled++
		run.release(ctx, event, due)
	}

	run.wait(settings.Drain)
	run.summary.Finished = settings.Now()
	return run.summary, ctx.Err()
}

// state is the run in progress. Everything a goroutine touches is guarded; the scheduler's own
// counters are not, because only the scheduler writes them.
type state struct {
	settings Settings
	known    *memory

	inFlight atomic.Int64
	group    sync.WaitGroup

	mu      sync.Mutex
	summary Summary
}

// release sends one event, without waiting for it.
func (s *state) release(ctx context.Context, event arrivals.Event, due time.Time) {
	action := s.settings.People.Draw(s.settings.Seed, event.Seq, s.settings.Date)
	request, err := client.Build(action, s.settings.Date, event.Seq, s.settings.Held, s.known)
	if err != nil {
		reason := ReasonUnbuildable
		if errors.Is(err, client.ErrNoReferenceYet) {
			reason = ReasonNoReference
		}
		s.unsent(action.Operation, reason)
		return
	}

	current := int(s.inFlight.Add(1))
	s.settings.Observer.InFlight(current)
	s.mu.Lock()
	if current > s.summary.PeakInFlight {
		s.summary.PeakInFlight = current
	}
	if s.settings.Expected > 0 && current > s.settings.Expected {
		s.summary.OverExpected++
	}
	s.mu.Unlock()

	s.group.Add(1)
	go func() {
		defer s.group.Done()
		result := s.settings.Sender.Send(ctx, request, due)
		s.settings.Observer.InFlight(int(s.inFlight.Add(-1)))
		s.record(request, result)
	}()
}

func (s *state) record(request client.Request, result client.Result) {
	// What the run posted is remembered before it is counted, so that the next dependent operation
	// has something real to name.
	if result.Transfer.Ref != "" {
		s.known.rememberTransfer(result.Transfer)
	}
	if result.Hold.Ref != "" {
		s.known.rememberHold(result.Hold)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	s.summary.Sent++
	s.summary.Outcomes[result.Outcome]++
	if s.summary.ByOperation[request.Operation] == nil {
		s.summary.ByOperation[request.Operation] = map[client.Outcome]int64{}
	}
	s.summary.ByOperation[request.Operation][result.Outcome]++
	if request.CurrencySubstituted {
		s.summary.Substituted++
	}
	if result.Attempts > 1 {
		s.summary.Retried++
	}
	if result.Outcome == client.Refused {
		s.summary.Refused++
	}

	s.summary.LatencyCount++
	s.summary.LatencySum += result.Latency
	if result.Latency > s.summary.LatencyMax {
		s.summary.LatencyMax = result.Latency
	}
	s.settings.Observer.Result(request, result)
}

func (s *state) unsent(operation, reason string) {
	s.mu.Lock()
	s.summary.Unsent[reason]++
	s.mu.Unlock()
	s.settings.Observer.Unsent(operation, reason)
}

func (s *state) behind(lag time.Duration) {
	s.mu.Lock()
	s.summary.TotalLag += lag
	if lag > s.summary.MaxLag {
		s.summary.MaxLag = lag
	}
	s.mu.Unlock()
	s.settings.Observer.Lag(lag)
}

// wait lets the outstanding requests finish, and gives up rather than hanging on an estate that has
// stopped answering entirely.
func (s *state) wait(drain time.Duration) {
	done := make(chan struct{})
	go func() {
		s.group.Wait()
		close(done)
	}()
	timer := time.NewTimer(drain)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
	}
}

// memory is what the run has created, and it is the client.References the request builder reads.
//
// Bounded on purpose: a run of a million events that remembered every transfer would spend its
// second half reading the first, and the memory would be the largest thing in the process.
type memory struct {
	mu        sync.Mutex
	transfers []client.Transfer
	holds     []client.Hold
	next      int
	reads     int
}

const remembered = 256

func newMemory() *memory {
	return &memory{
		transfers: make([]client.Transfer, 0, remembered),
		holds:     make([]client.Hold, 0, remembered),
	}
}

func (m *memory) rememberTransfer(transfer client.Transfer) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.transfers) < remembered {
		m.transfers = append(m.transfers, transfer)
		return
	}
	m.transfers[m.next%remembered] = transfer
	m.next++
}

func (m *memory) rememberHold(hold client.Hold) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.holds) < remembered {
		m.holds = append(m.holds, hold)
	}
	// A full hold buffer is dropped rather than overwritten: an overwritten hold is one nothing
	// will ever capture or release, and it would sit on the account's available balance for the
	// rest of the run.
}

// Transfer is a read of something the run posted. It does not consume: a transfer can be read any
// number of times.
func (m *memory) Transfer() (client.Transfer, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.transfers) == 0 {
		return client.Transfer{}, false
	}
	// Round the buffer rather than reading the same one every time: a run that read one transfer a
	// thousand times would be measuring a row the ledger has cached.
	m.reads++
	return m.transfers[m.reads%len(m.transfers)], true
}

// TakeTransfer removes a transfer and returns it. A reversal consumes what it reverses: reversing
// the same transfer twice is a conflict the ledger is right to refuse, and the driver would have
// manufactured it.
func (m *memory) TakeTransfer() (client.Transfer, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.transfers) == 0 {
		return client.Transfer{}, false
	}
	last := len(m.transfers) - 1
	transfer := m.transfers[last]
	m.transfers = m.transfers[:last]
	return transfer, true
}

// Hold consumes. A hold can be captured or released once, and handing the same one to two events
// would produce a conflict this driver invented.
func (m *memory) Hold() (client.Hold, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.holds) == 0 {
		return client.Hold{}, false
	}
	last := len(m.holds) - 1
	hold := m.holds[last]
	m.holds = m.holds[:last]
	return hold, true
}

// silent is the observer a run gets when nothing is watching.
type silent struct{}

func (silent) Result(client.Request, client.Result) {}
func (silent) Unsent(string, string)                {}
func (silent) Lag(time.Duration)                    {}
func (silent) InFlight(int)                         {}

// Describe renders the summary as the run report's outcome table.
func (s Summary) Describe() string {
	out := fmt.Sprintf("  scheduled %d, sent %d, unsent %d\n", s.Scheduled, s.Sent, s.unsentTotal())
	for _, outcome := range client.Outcomes() {
		out += fmt.Sprintf("  %-10s %d\n", outcome, s.Outcomes[outcome])
	}
	return out
}

func (s Summary) unsentTotal() int64 {
	var total int64
	for _, count := range s.Unsent {
		total += count
	}
	return total
}
