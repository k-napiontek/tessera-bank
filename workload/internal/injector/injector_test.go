package injector_test

import (
	"context"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/injector"
	"github.com/k-napiontek/tessera-bank/workload/internal/scenario"
)

const committed = "../../../contracts/workload/tessera-scenarios-v1.json"

func catalogue(t *testing.T) scenario.Catalogue {
	t.Helper()
	document, err := os.ReadFile(committed)
	if err != nil {
		t.Fatalf("reading the committed catalogue: %v", err)
	}
	decoded, err := scenario.Decode(document)
	if err != nil {
		t.Fatalf("decoding the committed catalogue: %v", err)
	}
	return decoded
}

func find(t *testing.T, condition scenario.Condition) scenario.Scenario {
	t.Helper()
	for _, each := range catalogue(t).Scenarios {
		if each.Condition == condition {
			return each
		}
	}
	t.Fatalf("the committed catalogue holds no %s scenario", condition)
	return scenario.Scenario{}
}

// fixture records what was asked of the estate instead of doing it. Every test in this file runs
// against one, which is what keeps `make test-workload` needing no Docker, no database and no
// broker - the property the Makefile's own comment says is a consequence of the design rather than
// a convenience.
type fixture struct {
	mu   sync.Mutex
	done []string
	fail error
}

func (f *fixture) record(what string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.done = append(f.done, what)
	return f.fail
}

func (f *fixture) calls() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.done...)
}

func (f *fixture) PauseContainer(_ context.Context, name string) error {
	return f.record("pause " + name)
}

func (f *fixture) ResumeContainer(_ context.Context, name string) error {
	return f.record("resume " + name)
}

func (f *fixture) SignalProcess(_ context.Context, role injector.Role, signal injector.Signal) error {
	return f.record("signal " + string(role) + " " + string(signal))
}

func (f *fixture) RunInContainer(_ context.Context, container string, argv ...string) ([]byte, error) {
	return nil, f.record("exec " + container + ": " + strings.Join(argv, " "))
}

func (f *fixture) StartInContainer(_ context.Context, container string, argv ...string) error {
	return f.record("start " + container + ": " + strings.Join(argv, " "))
}

type delayer struct {
	mu  sync.Mutex
	set []time.Duration
}

func (d *delayer) SetDelay(delay time.Duration) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.set = append(d.set, delay)
}

func (d *delayer) values() []time.Duration {
	d.mu.Lock()
	defer d.mu.Unlock()
	return append([]time.Duration(nil), d.set...)
}

type storm struct {
	mu      sync.Mutex
	started [][2]int
	stopped int
}

func (s *storm) Start(_ context.Context, perSecond, subjects int) func() {
	s.mu.Lock()
	s.started = append(s.started, [2]int{perSecond, subjects})
	s.mu.Unlock()
	return func() {
		s.mu.Lock()
		s.stopped++
		s.mu.Unlock()
	}
}

// settings wires an injector to fakes and to a sleep that returns at once, so a ninety-minute
// business window costs nothing to test. The durations it was asked to wait are recorded, because
// getting the compression arithmetic wrong is the defect that would be invisible in a real run.
func settings(t *testing.T, f *fixture, waited *[]time.Duration) injector.Settings {
	t.Helper()
	var mu sync.Mutex
	return injector.Settings{
		Fixture:           f,
		Proxy:             &delayer{},
		Storm:             &storm{},
		BrokerContainer:   "tessera-workload-kafka",
		DatabaseContainer: "tessera-workload-db",
		Compression:       720,
		Sleep: func(_ context.Context, d time.Duration) error {
			mu.Lock()
			*waited = append(*waited, d)
			mu.Unlock()
			return nil
		},
	}
}

func TestItRefusesAFixtureItCannotReach(t *testing.T) {
	if _, err := injector.New(injector.Settings{Compression: 1}); err == nil {
		t.Fatal("built an injector with nothing to inject into")
	}
	f := &fixture{}
	if _, err := injector.New(injector.Settings{Fixture: f, Compression: 0}); err == nil {
		t.Fatal("built an injector at a compression of zero, which is a division")
	}
}

func TestTheWindowIsCompressedTheWayTheRunIs(t *testing.T) {
	// A scenario states its window in minutes of the business day, so that it reads the same at any
	// dial setting. At 720x a ninety-minute condition is seven and a half seconds of wall clock, and
	// a run that held it for ninety real minutes would be a different exercise entirely.
	each := find(t, scenario.StuckOutbox)
	from := each.StartsAtMinute - 60

	applyAfter, holdFor, err := injector.Schedule(each, from, 720)
	if err != nil {
		t.Fatalf("Schedule: %v", err)
	}
	if want := time.Minute * 60 / 720; applyAfter != want {
		t.Errorf("applied after %s, want %s", applyAfter, want)
	}
	if want := time.Duration(each.HoldsForMinutes) * time.Minute / 720; holdFor != want {
		t.Errorf("held for %s, want %s", holdFor, want)
	}

	// A condition whose moment has already passed when the window opens is a scenario nobody can
	// run against that window, and saying so beats injecting it immediately and calling it the
	// declared time.
	if _, _, err := injector.Schedule(each, each.StartsAtMinute+1, 720); err == nil {
		t.Error("scheduled a condition that starts before the window it is run in")
	}
}

func TestEachConditionReachesTheEstateTheWayItsMechanismSays(t *testing.T) {
	cases := []struct {
		condition scenario.Condition
		wants     []string
	}{
		{scenario.StuckOutbox, []string{"pause tessera-workload-kafka", "resume tessera-workload-kafka"}},
		{scenario.ConsumerLag, []string{"signal fraud-scoring STOP", "signal fraud-scoring CONT"}},
		{scenario.PartialOutage, []string{"signal ledger STOP", "signal ledger CONT"}},
	}
	for _, each := range cases {
		t.Run(string(each.condition), func(t *testing.T) {
			target := find(t, each.condition)
			f := &fixture{}
			var waited []time.Duration
			engine, err := injector.New(settings(t, f, &waited))
			if err != nil {
				t.Fatalf("New: %v", err)
			}
			record, err := engine.Run(context.Background(), target, target.StartsAtMinute)
			if err != nil {
				t.Fatalf("Run: %v", err)
			}
			if !record.Injected {
				t.Fatalf("%s reported as not injected: %s", each.condition, record.NotInjectableBecause)
			}
			if got := f.calls(); len(got) != len(each.wants) || got[0] != each.wants[0] || got[len(got)-1] != each.wants[len(each.wants)-1] {
				t.Errorf("the estate saw %v, want %v", got, each.wants)
			}
		})
	}
}

func TestPoolExhaustionTakesTheLockTheScenarioNamesAndLetsItGo(t *testing.T) {
	target := find(t, scenario.PoolExhaustion)
	f := &fixture{}
	var waited []time.Duration
	engine, err := injector.New(settings(t, f, &waited))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, err := engine.Run(context.Background(), target, target.StartsAtMinute); err != nil {
		t.Fatalf("Run: %v", err)
	}

	calls := f.calls()
	if len(calls) != 2 {
		t.Fatalf("the estate saw %d calls, want an apply and a revert: %v", len(calls), calls)
	}
	if !strings.Contains(calls[0], target.Parameters.LockTable) ||
		!strings.Contains(calls[0], target.Parameters.LockMode) {
		t.Errorf("the lock taken was %q, and the scenario names %s in %s mode",
			calls[0], target.Parameters.LockTable, target.Parameters.LockMode)
	}
	// Held by pg_sleep rather than by a connection the injector keeps open: docker exec dies with
	// its client, and a lock released when the fixture blinked is not a condition.
	if !strings.Contains(calls[0], "pg_sleep") {
		t.Errorf("the lock is not held for a stated time: %q", calls[0])
	}
	if !strings.Contains(calls[1], "pg_terminate_backend") {
		t.Errorf("nothing releases the lock if the run ends early: %q", calls[1])
	}
}

func TestTheProxyIsGivenTheDeclaredDelayAndThenCleared(t *testing.T) {
	target := find(t, scenario.SlowDependency)
	f := &fixture{}
	var waited []time.Duration
	base := settings(t, f, &waited)
	hop := &delayer{}
	base.Proxy = hop
	engine, err := injector.New(base)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, err := engine.Run(context.Background(), target, target.StartsAtMinute); err != nil {
		t.Fatalf("Run: %v", err)
	}
	want := time.Duration(target.Parameters.ExtraLatencyMillis) * time.Millisecond
	if got := hop.values(); len(got) != 2 || got[0] != want || got[1] != 0 {
		t.Errorf("the hop was set to %v, want [%s 0s]", got, want)
	}
	// The delay is wall clock and is deliberately not compressed. A customer's second and a half is
	// a second and a half whatever rate the day is being replayed at, and dividing it by 720 would
	// inject a two-millisecond hiccup and call it a slow dependency.
	if hop.values()[0] != want {
		t.Errorf("the delay was scaled by the compression")
	}
}

func TestTheStormIsOfferedAtTheDeclaredRateAndStopped(t *testing.T) {
	target := find(t, scenario.RateLimitStorm)
	f := &fixture{}
	var waited []time.Duration
	base := settings(t, f, &waited)
	weather := &storm{}
	base.Storm = weather
	engine, err := injector.New(base)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, err := engine.Run(context.Background(), target, target.StartsAtMinute); err != nil {
		t.Fatalf("Run: %v", err)
	}
	if len(weather.started) != 1 {
		t.Fatalf("the storm started %d times", len(weather.started))
	}
	if weather.started[0] != [2]int{target.Parameters.RequestsPerSecond, target.Parameters.Subjects} {
		t.Errorf("the storm ran at %v, and the scenario declares %d/s over %d subjects",
			weather.started[0], target.Parameters.RequestsPerSecond, target.Parameters.Subjects)
	}
	if weather.stopped != 1 {
		t.Errorf("the storm was stopped %d times", weather.stopped)
	}
}

func TestAConditionThisFixtureCannotProduceIsRecordedRatherThanFailed(t *testing.T) {
	// WP-24's Constraint: the estate is not modified to make a fault injectable, and a condition
	// that cannot be produced without changing a component is a finding about that component's
	// testability. A finding is a value the run reports, not an error that stops it.
	target := find(t, scenario.ClockSkew)
	f := &fixture{}
	var waited []time.Duration
	engine, err := injector.New(settings(t, f, &waited))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	record, err := engine.Run(context.Background(), target, target.StartsAtMinute)
	if err != nil {
		t.Fatalf("Run returned an error for a condition it should have recorded: %v", err)
	}
	if record.Injected {
		t.Fatal("reported the clock skew as injected")
	}
	if record.NotInjectableBecause == "" {
		t.Error("recorded it as uninjected without saying why")
	}
	if len(f.calls()) != 0 {
		t.Errorf("touched the estate anyway: %v", f.calls())
	}
	if !errors.Is(record.Err, injector.ErrNotInjectable) {
		t.Errorf("record.Err is %v, want ErrNotInjectable", record.Err)
	}
}

func TestTheConditionIsRevertedEvenWhenTheRunIsCutShort(t *testing.T) {
	// A cancelled run must not leave a paused broker behind. The next run would boot against it,
	// every objective would be missed, and the reason would be an interrupted run from yesterday.
	target := find(t, scenario.StuckOutbox)
	f := &fixture{}
	var waited []time.Duration
	base := settings(t, f, &waited)
	ctx, cancel := context.WithCancel(context.Background())
	base.Sleep = func(c context.Context, _ time.Duration) error {
		// Applied, and then the run is cut short while the condition is being held.
		if len(f.calls()) > 0 {
			cancel()
			return c.Err()
		}
		return nil
	}
	engine, err := injector.New(base)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, err := engine.Run(ctx, target, target.StartsAtMinute); err == nil {
		t.Error("a cancelled run reported no error")
	}
	calls := f.calls()
	if len(calls) != 2 || !strings.HasPrefix(calls[1], "resume ") {
		t.Errorf("the broker was left paused: %v", calls)
	}
}
