// Package injector applies one declared condition to a running estate and takes it away again.
//
// It is a fixture, like everything else in this module. The line it holds is the one WP-24's
// Constraint draws: **the estate is not modified to make a fault injectable.** Everything here acts
// on a container the fixture booted or a process the fixture started, or on the controllable hop the
// fixture owns - never on a line of the estate's own configuration or code. Where a condition cannot
// be produced inside that line, it is reported as uninjected with the reason, because that is a
// finding about the component's testability rather than a failure of this package. That is the same
// honest failure mode WP-23 used when its report printed `nothing happened` against three components
// instead of leaving them out.
//
// What it does to the estate goes through the Fixture interface, so the whole package is tested
// against a recorder. `make test-workload` needs no Docker, no database and no broker, and the
// Makefile's own comment says why that is a property of the design rather than a convenience.
package injector

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/scenario"
)

// ErrNotInjectable is what a condition this fixture cannot produce returns. It is carried on the
// record rather than out of Run, because the run continues and the finding is the point.
var ErrNotInjectable = errors.New("injector: this fixture cannot produce the condition")

// Signal is what may be sent to a process the fixture started. Suspend rather than kill throughout:
// a killed JVM is one the injector cannot bring back, and a condition that cannot be reverted is a
// broken fixture rather than an exercise.
type Signal string

const (
	Suspend Signal = "STOP"
	Resume  Signal = "CONT"
)

// Role names a process the fixture started, since the injector does not start them itself.
type Role string

const (
	Ledger Role = "ledger"
	Scorer Role = "fraud-scoring"
)

// Fixture is what an injector may do to the estate the fixture booted.
type Fixture interface {
	PauseContainer(ctx context.Context, name string) error
	ResumeContainer(ctx context.Context, name string) error
	SignalProcess(ctx context.Context, role Role, signal Signal) error
	RunInContainer(ctx context.Context, container string, argv ...string) ([]byte, error)
	// StartInContainer runs something that outlives the call, which is what holding a lock for a
	// window means. Two methods rather than a flag, because "run this and tell me what it said" and
	// "start this and leave it running" fail in different ways and are read differently at the call
	// site.
	StartInContainer(ctx context.Context, container string, argv ...string) error
}

// Delayer is the controllable hop in front of the ledger. *proxy.Proxy is one.
type Delayer interface {
	SetDelay(delay time.Duration)
}

// Storm offers extra requests at the gateway on top of the day already being executed. The driver
// implements it, because it holds the sender, the wallet and the references a request needs, and
// duplicating any of those here is the rot F-61, F-64 and F-66 each record.
type Storm interface {
	Start(ctx context.Context, perSecond, subjects int) (stop func())
}

// Settings wires an injector to the estate the fixture booted.
type Settings struct {
	Fixture           Fixture
	Proxy             Delayer
	Storm             Storm
	BrokerContainer   string
	DatabaseContainer string
	// Compression, so a window stated in business minutes becomes wall clock the same way the
	// schedule does.
	Compression int
	// Sleep is injected so that a ninety-minute window costs a test nothing. It must return the
	// context's error when the run is cut short.
	Sleep func(ctx context.Context, d time.Duration) error
	// Log is where progress goes. Nil prints nothing, which is what a test wants.
	Log func(format string, args ...any)
}

// Injector applies conditions.
type Injector struct {
	settings Settings
}

// Record is what a run says about the condition it was given.
type Record struct {
	ScenarioID           string
	Condition            scenario.Condition
	Injected             bool
	NotInjectableBecause string
	AppliedAfter         time.Duration
	HeldFor              time.Duration
	Err                  error
}

// New refuses an injector that could not act if it were asked to.
func New(settings Settings) (*Injector, error) {
	if settings.Fixture == nil {
		return nil, errors.New("injector: no fixture to act on")
	}
	if settings.Compression <= 0 {
		return nil, fmt.Errorf("injector: a compression of %d is a division", settings.Compression)
	}
	if settings.Sleep == nil {
		settings.Sleep = wait
	}
	if settings.Log == nil {
		settings.Log = func(string, ...any) {}
	}
	return &Injector{settings: settings}, nil
}

// Schedule turns a scenario's business-day window into wall clock, relative to the moment the run's
// own window opens.
//
// A scenario states its window in minutes of the business day so that it reads the same at any dial
// setting, exactly as the day model does. The delay a slow-dependency condition adds is deliberately
// not compressed - see apply.
func Schedule(each scenario.Scenario, from bankday.Minute, compression int) (time.Duration, time.Duration, error) {
	if compression <= 0 {
		return 0, 0, fmt.Errorf("injector: a compression of %d is a division", compression)
	}
	if each.StartsAtMinute < from {
		return 0, 0, fmt.Errorf("injector: %s starts at %s and this run's window opens at %s, so "+
			"its moment has already passed", each.ScenarioID, each.StartsAtMinute, from)
	}
	into := time.Duration(each.StartsAtMinute-from) * time.Minute / time.Duration(compression)
	hold := time.Duration(each.HoldsForMinutes) * time.Minute / time.Duration(compression)
	return into, hold, nil
}

// Run waits for the condition's moment, applies it, holds it, and reverts it.
//
// It is meant to run beside the driver's own execution rather than instead of it: a condition
// applied between two runs measures a maintenance window, which is the thing this exercise exists
// not to be.
func (i *Injector) Run(ctx context.Context, each scenario.Scenario, from bankday.Minute) (Record, error) {
	record := Record{ScenarioID: each.ScenarioID, Condition: each.Condition}

	into, hold, err := Schedule(each, from, i.settings.Compression)
	if err != nil {
		return record, err
	}
	record.AppliedAfter, record.HeldFor = into, hold

	apply, revert, reason := i.actions(each)
	if reason != "" {
		record.NotInjectableBecause = reason
		record.Err = fmt.Errorf("%w: %s", ErrNotInjectable, reason)
		i.settings.Log("  %s is not injectable by this fixture: %s\n", each.ScenarioID, reason)
		return record, nil
	}

	i.settings.Log("  %s in %s, held for %s\n", each.ScenarioID, into.Round(time.Millisecond),
		hold.Round(time.Millisecond))
	if err := i.settings.Sleep(ctx, into); err != nil {
		return record, err
	}

	if err := apply(ctx); err != nil {
		return record, fmt.Errorf("injector: applying %s: %w", each.ScenarioID, err)
	}
	record.Injected = true
	i.settings.Log("  %s applied\n", each.ScenarioID)

	held := i.settings.Sleep(ctx, hold)

	// Reverted whatever happened while it was held, and on a context of its own. A cancelled run
	// that left a paused broker behind would be booted against tomorrow, every objective would be
	// missed, and the reason would be an interruption nobody remembers.
	reverting, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
	defer cancel()
	if err := revert(reverting); err != nil {
		return record, fmt.Errorf("injector: reverting %s: %w", each.ScenarioID, err)
	}
	i.settings.Log("  %s reverted\n", each.ScenarioID)
	return record, held
}

type action func(ctx context.Context) error

// actions maps a condition onto what the fixture does, or onto the reason it cannot.
func (i *Injector) actions(each scenario.Scenario) (action, action, string) {
	fixture := i.settings.Fixture
	switch each.Condition {

	case scenario.StuckOutbox:
		broker := i.settings.BrokerContainer
		return func(ctx context.Context) error { return fixture.PauseContainer(ctx, broker) },
			func(ctx context.Context) error { return fixture.ResumeContainer(ctx, broker) },
			""

	case scenario.ConsumerLag:
		return func(ctx context.Context) error { return fixture.SignalProcess(ctx, Scorer, Suspend) },
			func(ctx context.Context) error { return fixture.SignalProcess(ctx, Scorer, Resume) },
			""

	case scenario.PartialOutage:
		return func(ctx context.Context) error { return fixture.SignalProcess(ctx, Ledger, Suspend) },
			func(ctx context.Context) error { return fixture.SignalProcess(ctx, Ledger, Resume) },
			""

	case scenario.SlowDependency:
		if i.settings.Proxy == nil {
			return nil, nil, "the run hosts no controllable hop, so there is nowhere to add a delay " +
				"the ledger's own timer cannot see"
		}
		// Wall clock, deliberately uncompressed. A customer's second and a half is a second and a
		// half whatever rate the day is being replayed at, and dividing it by the compression would
		// inject a two-millisecond hiccup and call it a slow dependency.
		delay := time.Duration(each.Parameters.ExtraLatencyMillis) * time.Millisecond
		return func(context.Context) error { i.settings.Proxy.SetDelay(delay); return nil },
			func(context.Context) error { i.settings.Proxy.SetDelay(0); return nil },
			""

	case scenario.RateLimitStorm:
		if i.settings.Storm == nil {
			return nil, nil, "the run offers no way to send requests beside the day it is executing"
		}
		var stop func()
		return func(ctx context.Context) error {
				stop = i.settings.Storm.Start(ctx, each.Parameters.RequestsPerSecond, each.Parameters.Subjects)
				return nil
			},
			func(context.Context) error {
				if stop != nil {
					stop()
				}
				return nil
			},
			""

	case scenario.PoolExhaustion:
		return i.lock(each), i.unlock(), ""

	case scenario.ClockSkew:
		// Declared, and not producible from here. The value date a transfer is accounted for under
		// comes from PostgreSQL's own now(), so moving the two sides of the boundary apart means
		// moving a clock inside a container - which needs SYS_TIME on a kernel shared with the host,
		// or a faketime shim built into the image. Both are changes to the estate's runtime rather
		// than to this fixture, and WP-24's Constraint refuses them. Skewing the driver's date
		// instead was considered and is not the same condition: the ledger stamps its own value
		// dates, so a driver that thinks it is Tuesday changes nothing but its own idempotency keys.
		return nil, nil, "the value date comes from PostgreSQL's own now(), and moving a container's " +
			"clock needs SYS_TIME on a shared kernel or a faketime shim in the image - both changes " +
			"to the estate rather than to the fixture"
	}
	return nil, nil, fmt.Sprintf("no action is defined for the condition %q", each.Condition)
}

// lock holds a table lock for the declared window from inside the database container.
//
// pg_sleep holds it rather than a connection this process keeps open, because docker exec dies with
// its client and a lock released when the fixture blinked is not a condition. Both the table and the
// mode are enums in the contract's own schema, checked by contracts/check-workload-scenarios.py, so
// neither reaches SQL as anything the catalogue did not already bound.
func (i *Injector) lock(each scenario.Scenario) action {
	return func(ctx context.Context) error {
		seconds := max(int((time.Duration(each.HoldsForMinutes) * time.Minute /
			time.Duration(i.settings.Compression)).Seconds()), 1)
		statement := fmt.Sprintf(
			"BEGIN; LOCK TABLE %s IN %s MODE; SELECT pg_sleep(%d); COMMIT;",
			each.Parameters.LockTable, each.Parameters.LockMode, seconds+lockMargin)
		// Detached, because the lock has to be held while the run carries on around it and this
		// statement does not return until pg_sleep does. A goroutine around a blocking call would
		// leave the moment the lock was actually taken undefined, and the revert below could
		// overtake it.
		return i.settings.Fixture.StartInContainer(ctx, i.settings.DatabaseContainer,
			"psql", "-U", "tessera", "-d", "tessera", "-c", statement)
	}
}

// lockMargin keeps the lock alive a little past the declared window, so that the revert below is
// what ends it rather than a race between two timers.
const lockMargin = 5

func (i *Injector) unlock() action {
	return func(ctx context.Context) error {
		_, err := i.settings.Fixture.RunInContainer(ctx, i.settings.DatabaseContainer,
			"psql", "-U", "tessera", "-d", "tessera", "-c",
			"SELECT pg_terminate_backend(pid) FROM pg_stat_activity "+
				"WHERE query LIKE '%pg_sleep%' AND pid <> pg_backend_pid();")
		return err
	}
}

func wait(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// --------------------------------------------------------------------------------------------
// The local fixture: what the estate looks like when estate-up.sh booted it
// --------------------------------------------------------------------------------------------

// Local acts on the containers and processes workload/scripts/estate-up.sh started on this machine.
//
// Processes are addressed by process **group**, read from a file the script writes. `go run` and
// `gradlew` both exec a child - a compiled binary and a JVM - so signalling the parent alone stops
// a wrapper and leaves the thing that matters running. F-73 records the same trap costing a whole
// run when it was teardown rather than injection.
type Local struct {
	// RunDir is where estate-up.sh writes one <role>.pgid per process it started.
	RunDir string
}

func (l Local) PauseContainer(ctx context.Context, name string) error {
	return docker(ctx, "pause", name)
}

func (l Local) ResumeContainer(ctx context.Context, name string) error {
	return docker(ctx, "unpause", name)
}

func (l Local) SignalProcess(_ context.Context, role Role, signal Signal) error {
	group, err := l.group(role)
	if err != nil {
		return err
	}
	number := map[Signal]syscall.Signal{Suspend: syscall.SIGSTOP, Resume: syscall.SIGCONT}[signal]
	if number == 0 {
		return fmt.Errorf("injector: no signal called %q", signal)
	}
	if err := syscall.Kill(-group, number); err != nil {
		return fmt.Errorf("injector: SIG%s to the %s process group %d: %w", signal, role, group, err)
	}
	return nil
}

func (l Local) RunInContainer(ctx context.Context, container string, argv ...string) ([]byte, error) {
	full := append([]string{"exec", "-e", "PGPASSWORD=tessera", container}, argv...)
	out, err := exec.CommandContext(ctx, "docker", full...).CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("injector: docker exec %s: %w: %s", container, err, strings.TrimSpace(string(out)))
	}
	return out, nil
}

func (l Local) StartInContainer(ctx context.Context, container string, argv ...string) error {
	full := append([]string{"exec", "-d", "-e", "PGPASSWORD=tessera", container}, argv...)
	out, err := exec.CommandContext(ctx, "docker", full...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("injector: docker exec -d %s: %w: %s", container, err, strings.TrimSpace(string(out)))
	}
	return nil
}

func (l Local) group(role Role) (int, error) {
	if l.RunDir == "" {
		return 0, fmt.Errorf("injector: no run directory, so the %s process cannot be addressed", role)
	}
	path := filepath.Join(l.RunDir, string(role)+".pgid")
	body, err := os.ReadFile(path)
	if err != nil {
		return 0, fmt.Errorf("injector: reading %s: %w - estate-up.sh writes it when it starts the "+
			"process, so a run driven against an estate somebody else booted cannot signal it", path, err)
	}
	group, err := strconv.Atoi(strings.TrimSpace(string(body)))
	if err != nil || group <= 0 {
		return 0, fmt.Errorf("injector: %s holds %q, which is not a process group", path, strings.TrimSpace(string(body)))
	}
	return group, nil
}

func docker(ctx context.Context, argv ...string) error {
	out, err := exec.CommandContext(ctx, "docker", argv...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("injector: docker %s: %w: %s", strings.Join(argv, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}
