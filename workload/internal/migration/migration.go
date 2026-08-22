// Package migration applies a schema migration to a live ledger while a day is being driven at it.
//
// It is the exercise master-plan.md names as a motivating skill and that nothing in this repository
// previously allowed anyone to attempt: changing the shape of a database while money is moving
// through it. A migration applied to an idle database demonstrates nothing about applying one to a
// busy one, and a migration applied between two runs measures a maintenance window - which is the
// thing this exercise exists not to be.
//
// **The migration is the exercise's own, never the ledger's.** The SQL lives under workload/ and is
// applied with its own Flyway history table, so services/ledger-persistence's migration set is
// untouched: a package that measures should not leave a permanent row in the schema history of the
// thing it measured. It is dropped again afterwards, which is also what lets the exercise be run
// twice. WP-24's fifth task decision records the reasoning, and ADR 0018 records why this is not an
// eighth entry in the scenario catalogue.
//
// Like internal/injector, everything here acts on a container the fixture booted. Nothing in the
// estate changes to make the exercise possible.
package migration

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Image is the Flyway the exercise runs, pinned to the major version the ledger itself runs -
// services/ledger-persistence/build.gradle.kts takes flyway-core from Boot 3.2's BOM, which is
// Flyway 9. An exercise that demonstrated what a *different* Flyway does to a busy database would
// be demonstrating the wrong thing.
const Image = "flyway/flyway:9.22.3-alpine"

// ErrNothingApplied is what a run that migrated nothing returns.
//
// It has its own error because it is the failure this exercise is most likely to produce and least
// likely to notice: Flyway is idempotent by design, so a second run over a history table that
// already records the migration prints "No migration necessary" and exits **zero**. The capture
// would then be a plausible-looking record of a lock nobody took. F-86 is the same shape one
// package earlier, where a sweep in which nothing was posted read as an estate full of dead
// components, and workload-run --require-postings is the control it produced.
var ErrNothingApplied = errors.New("migration: the run applied no migration")

// Variant is how the index is built. The two are the whole point of running this twice: the same
// index one way holds a lock that blocks every write to the table, and the other way does not.
type Variant string

const (
	Blocking   Variant = "blocking"
	Concurrent Variant = "concurrent"
)

// Fixture is what this package may do to the estate the fixture booted. RunInContainer is
// internal/injector's, and *injector.Local already satisfies it - reusing it rather than growing a
// second copy of the same docker-exec helper, which is the rot F-61, F-64 and F-66 each record.
// RunImage is the one capability the injector has no reason to carry.
type Fixture interface {
	RunInContainer(ctx context.Context, container string, argv ...string) ([]byte, error)
	// RunImage runs a one-shot container of another image, joined to the network of the container
	// named by joinNetworkOf and with mountHostDir mounted at /flyway/sql.
	RunImage(ctx context.Context, image, joinNetworkOf, mountHostDir string, argv ...string) ([]byte, error)
}

// Settings wires the exercise to the estate the fixture booted.
type Settings struct {
	Fixture           Fixture
	DatabaseContainer string
	// MigrationsDir is the host directory holding exactly one V1__*.sql, mounted into the Flyway
	// container. One directory per variant, so that each run applies exactly one migration.
	MigrationsDir string
	HistoryTable  string
	Variant       Variant
	// Table and Index are what the migration touches. Both are needed after the fact rather than
	// before it: the lock sampler watches the table, and the verification and the rollback name the
	// index. Neither reaches SQL as anything but an identifier this package was handed.
	Table string
	Index string

	// RunLog is where the driver's own output goes, and StartMarker is the line it prints when the
	// day begins. After is how far into the day the migration is applied.
	RunLog      string
	StartMarker string
	After       time.Duration
	// WaitFor bounds how long the day is waited for. A run that never starts is a broken fixture,
	// and waiting forever for one turns that into a hang nobody can read.
	WaitFor time.Duration

	// Sleep and Now are injected so a test costs nothing and reads no clock.
	Sleep func(ctx context.Context, d time.Duration) error
	Now   func() time.Time
	Log   func(format string, args ...any)
}

// Record is what the exercise says about the migration it applied.
type Record struct {
	Variant      Variant       `json:"variant"`
	Table        string        `json:"table"`
	Index        string        `json:"index"`
	HistoryTable string        `json:"historyTable"`
	Statement    string        `json:"statement"`
	Image        string        `json:"image"`
	Took         time.Duration `json:"-"`
	TookSeconds  float64       `json:"tookSeconds"`
	FlywayOutput string        `json:"flywayOutput"`
	Locks        LockSummary   `json:"locks"`
}

// Migration applies one.
type Migration struct {
	settings Settings
}

const defaultMarker = "== Run =="
const defaultWaitFor = 10 * time.Minute
const ledgerHistoryTable = "flyway_schema_history"

// New refuses an exercise that could not be applied, or that would be applied to the wrong thing.
func New(settings Settings) (*Migration, error) {
	if settings.Fixture == nil {
		return nil, errors.New("migration: no fixture to act on")
	}
	if settings.DatabaseContainer == "" {
		return nil, errors.New("migration: no database container to migrate")
	}
	if settings.MigrationsDir == "" {
		return nil, errors.New("migration: no migrations directory to apply")
	}
	if settings.HistoryTable == "" {
		return nil, errors.New("migration: no history table for the exercise's own migration")
	}
	if settings.HistoryTable == ledgerHistoryTable {
		return nil, fmt.Errorf("migration: %q is the ledger's own history table, and this exercise "+
			"must not leave a row in the schema history of the thing it measures",
			settings.HistoryTable)
	}
	if settings.Table == "" {
		return nil, errors.New("migration: no table for the lock sampler to watch")
	}
	if settings.Index == "" {
		return nil, errors.New("migration: no index to verify and to drop again")
	}
	if settings.Variant != Blocking && settings.Variant != Concurrent {
		return nil, fmt.Errorf("migration: %q is not a variant; it is %s or %s",
			settings.Variant, Blocking, Concurrent)
	}
	if settings.Sleep == nil {
		settings.Sleep = wait
	}
	if settings.Now == nil {
		settings.Now = time.Now
	}
	if settings.Log == nil {
		settings.Log = func(string, ...any) {}
	}
	if settings.StartMarker == "" {
		settings.StartMarker = defaultMarker
	}
	if settings.WaitFor == 0 {
		settings.WaitFor = defaultWaitFor
	}
	return &Migration{settings: settings}, nil
}

// flywayArgv is the invocation, and every flag on it was arrived at by running it rather than by
// reading about it.
//
//   - baselineOnMigrate and baselineVersion, because the schema this runs against already holds the
//     ledger's eight migrations under the ledger's own history table. Without them Flyway refuses
//     outright: "Found non-empty schema(s) but no schema history table".
//   - cleanDisabled, because nothing here should ever be able to drop the ledger's schema.
//   - postgresql.transactional.lock, for the concurrent variant only, and it is the flag whose
//     absence does not fail. See below.
func (m *Migration) flywayArgv() []string {
	argv := []string{
		"-url=jdbc:postgresql://localhost:5432/tessera",
		"-user=tessera",
		"-password=tessera",
		"-table=" + m.settings.HistoryTable,
		"-locations=filesystem:/flyway/sql",
		"-baselineOnMigrate=true",
		"-baselineVersion=0",
		"-cleanDisabled=true",
	}
	if m.settings.Variant == Concurrent {
		// The one setting in this file that fails by hanging rather than by erroring, so it is the
		// one worth reading twice.
		//
		// Flyway 9 already knows CREATE INDEX CONCURRENTLY cannot run inside a transaction - it
		// detects the statement and prints "[non-transactional]" without being told, so the
		// executeInTransaction=false script setting is redundant here and is deliberately not
		// shipped. What it does *not* do is release its own schema-history lock, which it holds on a
		// second connection that sits idle in transaction for the length of the migration. CREATE
		// INDEX CONCURRENTLY waits for every transaction that could see the table to finish,
		// including that one, so the two wait for each other and the migration never returns.
		//
		// Measured here on PostgreSQL 16.15 with Flyway 9.22.3: without this flag the statement was
		// still `active`, `wait_event_type = Lock`, with Flyway's own session `idle in transaction`
		// beside it, until it was killed. No error, no timeout, and the bank still serving - which
		// is exactly how an operator would meet it at three in the morning.
		argv = append(argv, "-postgresql.transactional.lock=false")
	}
	return append(argv, "migrate")
}

// WaitForMoment blocks until the day is actually being executed and then until the declared offset
// into it.
//
// The driver prints its start marker after seeding and after the opening scrapes, immediately before
// it begins the day, so the marker is a fact rather than an estimate. Sleeping on a guess at Gradle
// and Kafka boot times instead would put the migration anywhere from before the first request to
// after the last, and a capture whose moment depends on how busy the laptop was is a capture nobody
// can reproduce. estate-up.sh holds itself to the same rule for every component it boots.
func (m *Migration) WaitForMoment(ctx context.Context) error {
	if m.settings.RunLog == "" {
		return errors.New("migration: no run log to watch for the day to start")
	}
	started := m.settings.Now()
	for {
		seen, err := m.dayHasStarted()
		if err != nil {
			return err
		}
		if seen {
			break
		}
		if m.settings.Now().Sub(started) > m.settings.WaitFor {
			return fmt.Errorf("migration: %s never appeared in %s within %s, so the day this "+
				"migration was to be applied during never started",
				m.settings.StartMarker, m.settings.RunLog, m.settings.WaitFor)
		}
		if err := m.settings.Sleep(ctx, pollEvery); err != nil {
			return err
		}
	}
	m.settings.Log("  the day has started; applying the migration %s into it\n", m.settings.After)
	return m.settings.Sleep(ctx, m.settings.After)
}

const pollEvery = 250 * time.Millisecond

// Apply samples the locks, runs Flyway, and refuses a run that migrated nothing.
func (m *Migration) Apply(ctx context.Context) (Record, error) {
	record := Record{
		Variant:      m.settings.Variant,
		Table:        m.settings.Table,
		Index:        m.settings.Index,
		HistoryTable: m.settings.HistoryTable,
		Image:        Image,
	}

	stop := m.sample(ctx)
	began := m.settings.Now()
	output, err := m.settings.Fixture.RunImage(ctx, Image,
		m.settings.DatabaseContainer, m.settings.MigrationsDir, m.flywayArgv()...)
	record.Took = m.settings.Now().Sub(began)
	record.TookSeconds = record.Took.Seconds()
	record.FlywayOutput = strings.TrimSpace(string(output))
	record.Locks = summarise(stop())

	if err != nil {
		return record, fmt.Errorf("migration: running Flyway: %w\n%s", err, record.FlywayOutput)
	}
	if !applied(record.FlywayOutput) {
		return record, fmt.Errorf("%w - Flyway exited zero and migrated nothing, which is what it "+
			"does when its history table %q already records this migration. The capture would be a "+
			"record of a lock nobody took. Roll the exercise back, or point it at a database that "+
			"has not seen it:\n%s",
			ErrNothingApplied, m.settings.HistoryTable, record.FlywayOutput)
	}
	if err := m.verify(ctx); err != nil {
		return record, err
	}
	return record, nil
}

// applied reads Flyway's own account of what it did.
//
// The string rather than the exit code, because the exit code is zero either way. Flyway prints
// "Successfully applied N migrations" when it did something and "No migration necessary" when it
// did not, and only the first of those is this exercise.
func applied(output string) bool {
	return strings.Contains(output, "Successfully applied 1 migration") ||
		strings.Contains(output, "Successfully applied 1 migrations")
}

// verify asks the database rather than Flyway.
//
// Two different claims: that Flyway ran a statement, and that the index exists and is usable. A
// CREATE INDEX CONCURRENTLY whose build fails leaves the index behind marked invalid, and an invalid
// index is not the migration this exercise says it applied.
func (m *Migration) verify(ctx context.Context) error {
	out, err := m.settings.Fixture.RunInContainer(ctx, m.settings.DatabaseContainer,
		"psql", "-U", "tessera", "-d", "tessera", "-tA", "-F", "|", "-c",
		fmt.Sprintf("SELECT c.relname, i.indisvalid FROM pg_class c JOIN pg_index i "+
			"ON i.indexrelid = c.oid WHERE c.relname = '%s'", m.settings.Index))
	if err != nil {
		return fmt.Errorf("migration: asking the database whether %s exists: %w", m.settings.Index, err)
	}
	line := strings.TrimSpace(string(out))
	if line == "" {
		return fmt.Errorf("migration: Flyway reported applying the migration and the database holds "+
			"no index %s, so the two disagree about what happened", m.settings.Index)
	}
	if !strings.HasSuffix(line, "|t") {
		return fmt.Errorf("migration: %s exists and PostgreSQL marks it invalid (%s), which is what a "+
			"failed concurrent build leaves behind - it is not the migration this exercise applied",
			m.settings.Index, line)
	}
	return nil
}

// Rollback drops the index and the exercise's history table.
//
// So that the exercise leaves the ledger exactly as it found it, and so that it can be run again -
// without this, the second run finds the migration already recorded, applies nothing and is refused
// by ErrNothingApplied, which is correct and useless.
func (m *Migration) Rollback(ctx context.Context) error {
	statement := fmt.Sprintf("DROP INDEX IF EXISTS %s; DROP TABLE IF EXISTS %s;",
		m.settings.Index, m.settings.HistoryTable)
	if _, err := m.settings.Fixture.RunInContainer(ctx, m.settings.DatabaseContainer,
		"psql", "-U", "tessera", "-d", "tessera", "-c", statement); err != nil {
		return fmt.Errorf("migration: rolling the exercise back: %w", err)
	}
	m.settings.Log("  rolled back: %s and %s are gone\n", m.settings.Index, m.settings.HistoryTable)
	return nil
}

func (m *Migration) dayHasStarted() (bool, error) {
	text, err := readFile(m.settings.RunLog)
	if err != nil {
		return false, fmt.Errorf("migration: reading the run log: %w", err)
	}
	return strings.Contains(text, m.settings.StartMarker), nil
}

func wait(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
