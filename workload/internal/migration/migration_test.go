package migration

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// recorder stands in for Docker, so this package's tests need no daemon - the property
// internal/injector's tests already hold and that make test-workload depends on.
type recorder struct {
	inContainer [][]string
	images      []imageCall
	psqlReplies []string
	psqlErr     error
	imageReply  string
	imageErr    error
}

type imageCall struct {
	image   string
	network string
	mount   string
	argv    []string
}

func (r *recorder) RunInContainer(_ context.Context, container string, argv ...string) ([]byte, error) {
	r.inContainer = append(r.inContainer, append([]string{container}, argv...))
	if r.psqlErr != nil {
		return nil, r.psqlErr
	}
	if len(r.psqlReplies) == 0 {
		return nil, nil
	}
	reply := r.psqlReplies[0]
	if len(r.psqlReplies) > 1 {
		r.psqlReplies = r.psqlReplies[1:]
	}
	return []byte(reply), nil
}

func (r *recorder) RunImage(_ context.Context, image, network, mount string, argv ...string) ([]byte, error) {
	r.images = append(r.images, imageCall{image: image, network: network, mount: mount, argv: argv})
	return []byte(r.imageReply), r.imageErr
}

// appliedOutput is real Flyway 9.22.3 output, captured against a PostgreSQL 16 container rather
// than composed here. A parser proved against invented output proves nothing about the parser.
const appliedOutput = `Flyway Community Edition 9.22.3 by Redgate

Database: jdbc:postgresql://localhost:5432/tessera (PostgreSQL 16.15)
Schema history table "public"."workload_exercise_blocking_history" does not exist yet
Successfully validated 1 migration (execution time 00:00.010s)
Creating Schema History table "public"."workload_exercise_blocking_history" with baseline ...
Successfully baselined schema with version: 0
Current version of schema "public": 0
Migrating schema "public" to version "1 - posting exercise ix"
Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.005s)`

// nothingToDoOutput is what Flyway says when the history table already records the migration. It
// exits zero, which is the whole problem: the exercise would report a lock nobody took.
const nothingToDoOutput = `Flyway Community Edition 9.22.3 by Redgate

Database: jdbc:postgresql://localhost:5432/tessera (PostgreSQL 16.15)
Current version of schema "public": 1
Schema "public" is up to date. No migration necessary.`

func settings(t *testing.T, r *recorder) Settings {
	t.Helper()
	return Settings{
		Fixture:           r,
		DatabaseContainer: "tessera-dataset-db",
		MigrationsDir:     t.TempDir(),
		HistoryTable:      "workload_exercise_blocking_history",
		Variant:           Blocking,
		Table:             "posting",
		Index:             "posting_exercise_ix",
		Sleep:             func(context.Context, time.Duration) error { return nil },
		Now:               func() time.Time { return time.Unix(0, 0) },
	}
}

func TestNewRefusesAMigrationItCouldNotApply(t *testing.T) {
	for _, each := range []struct {
		name   string
		change func(*Settings)
		want   string
	}{
		{"no fixture", func(s *Settings) { s.Fixture = nil }, "no fixture"},
		{"no database container", func(s *Settings) { s.DatabaseContainer = "" }, "database container"},
		{"no migrations directory", func(s *Settings) { s.MigrationsDir = "" }, "migrations directory"},
		{"no history table", func(s *Settings) { s.HistoryTable = "" }, "history table"},
		{"the ledger's own history table", func(s *Settings) { s.HistoryTable = "flyway_schema_history" },
			"the ledger's own"},
		{"no table", func(s *Settings) { s.Table = "" }, "table"},
		{"no index", func(s *Settings) { s.Index = "" }, "index"},
		{"an unknown variant", func(s *Settings) { s.Variant = "sideways" }, "sideways"},
	} {
		t.Run(each.name, func(t *testing.T) {
			s := settings(t, &recorder{})
			each.change(&s)
			_, err := New(s)
			if err == nil {
				t.Fatalf("accepted settings with %s", each.name)
			}
			if !strings.Contains(err.Error(), each.want) {
				t.Fatalf("error %q does not name %q", err, each.want)
			}
		})
	}
}

// The setting this test pins is the one whose absence does not fail. Without it Flyway's own
// schema-history lock sits idle in transaction on a second connection, CREATE INDEX CONCURRENTLY
// waits for every transaction that could see the table, and the migration never returns - silently,
// with the bank still serving. Measured on PostgreSQL 16.15 with Flyway 9.22.3.
func TestTheConcurrentVariantDisablesFlywaysTransactionalLock(t *testing.T) {
	r := &recorder{imageReply: appliedOutput, psqlReplies: []string{"posting_exercise_ix|t"}}
	s := settings(t, r)
	s.Variant = Concurrent
	s.HistoryTable = "workload_exercise_concurrent_history"

	m, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Apply(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(r.images) != 1 {
		t.Fatalf("ran %d images, wanted 1", len(r.images))
	}
	argv := strings.Join(r.images[0].argv, " ")
	if !strings.Contains(argv, "-postgresql.transactional.lock=false") {
		t.Fatalf("the concurrent variant did not disable Flyway's transactional lock: %s", argv)
	}
}

func TestTheBlockingVariantLeavesFlywaysTransactionalLockAlone(t *testing.T) {
	r := &recorder{imageReply: appliedOutput, psqlReplies: []string{"posting_exercise_ix|t"}}
	m, err := New(settings(t, r))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Apply(context.Background()); err != nil {
		t.Fatal(err)
	}
	if argv := strings.Join(r.images[0].argv, " "); strings.Contains(argv, "transactional.lock") {
		t.Fatalf("the blocking variant should not need the lock flag: %s", argv)
	}
}

// Every Flyway run here is against a schema that already holds the ledger's eight migrations under
// its own history table, so without a baseline Flyway refuses outright: "Found non-empty schema(s)
// but no schema history table". Measured, and the reason this flag is not optional.
func TestEveryRunBaselinesItsOwnHistoryTable(t *testing.T) {
	r := &recorder{imageReply: appliedOutput, psqlReplies: []string{"posting_exercise_ix|t"}}
	m, err := New(settings(t, r))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Apply(context.Background()); err != nil {
		t.Fatal(err)
	}
	argv := strings.Join(r.images[0].argv, " ")
	for _, want := range []string{
		"-baselineOnMigrate=true",
		"-baselineVersion=0",
		"-table=workload_exercise_blocking_history",
		"-cleanDisabled=true",
		"migrate",
	} {
		if !strings.Contains(argv, want) {
			t.Errorf("the Flyway invocation is missing %s: %s", want, argv)
		}
	}
}

// The control that stops this exercise producing a capture of a migration nobody applied. Flyway is
// idempotent by design, so a second run over the same history table succeeds and does nothing - the
// F-86 shape exactly, where a run in which nothing happened read as an estate full of dead
// components.
func TestARunThatAppliedNothingIsRefusedRatherThanReported(t *testing.T) {
	r := &recorder{imageReply: nothingToDoOutput}
	m, err := New(settings(t, r))
	if err != nil {
		t.Fatal(err)
	}
	_, err = m.Apply(context.Background())
	if err == nil {
		t.Fatal("a run that applied no migration was reported as a migration under traffic")
	}
	if !errors.Is(err, ErrNothingApplied) {
		t.Fatalf("error %q is not ErrNothingApplied", err)
	}
}

// Flyway saying it applied a migration and the index existing are two different claims, and the
// second is the one the exercise rests on.
func TestTheIndexIsVerifiedInTheDatabaseRatherThanTakenFromFlyway(t *testing.T) {
	r := &recorder{imageReply: appliedOutput, psqlReplies: []string{""}}
	m, err := New(settings(t, r))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Apply(context.Background()); err == nil {
		t.Fatal("accepted Flyway's word for an index the database does not hold")
	}
}

// An index built by CREATE INDEX CONCURRENTLY can be left behind invalid when the build fails, and
// an invalid index is not the migration the exercise claims to have applied.
func TestAnInvalidIndexIsRefused(t *testing.T) {
	r := &recorder{imageReply: appliedOutput, psqlReplies: []string{"posting_exercise_ix|f"}}
	s := settings(t, r)
	s.Variant = Concurrent
	s.HistoryTable = "workload_exercise_concurrent_history"
	m, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Apply(context.Background()); err == nil {
		t.Fatal("accepted an index PostgreSQL marks invalid")
	}
}

func TestApplyRecordsHowLongTheMigrationTook(t *testing.T) {
	r := &recorder{imageReply: appliedOutput, psqlReplies: []string{"posting_exercise_ix|t"}}
	s := settings(t, r)
	at := time.Unix(0, 0)
	s.Now = func() time.Time {
		at = at.Add(3 * time.Second)
		return at
	}
	m, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	record, err := m.Apply(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if record.Took <= 0 {
		t.Fatalf("recorded a migration that took %s", record.Took)
	}
	if record.Variant != Blocking {
		t.Fatalf("recorded the variant as %q", record.Variant)
	}
	if record.FlywayOutput == "" {
		t.Fatal("Flyway's own account of the run was not kept")
	}
}

// Rollback exists so the exercise can be run twice. Without it the second run finds the migration
// already in its history table, applies nothing, and is refused by the control above - which is
// correct but useless.
func TestRollbackDropsTheIndexAndTheHistoryTable(t *testing.T) {
	r := &recorder{}
	m, err := New(settings(t, r))
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Rollback(context.Background()); err != nil {
		t.Fatal(err)
	}
	all := ""
	for _, call := range r.inContainer {
		all += strings.Join(call, " ") + "\n"
	}
	if !strings.Contains(all, "DROP INDEX") || !strings.Contains(all, "posting_exercise_ix") {
		t.Errorf("the index was not dropped: %s", all)
	}
	if !strings.Contains(all, "DROP TABLE") || !strings.Contains(all, "workload_exercise_blocking_history") {
		t.Errorf("the history table was not dropped: %s", all)
	}
}

// The moment matters: a migration applied between two runs measures a maintenance window, which is
// the thing this exercise exists not to be. The driver prints "== Run ==" after seeding and after
// the opening scrapes, immediately before it starts executing the day, so that line is the signal -
// waited on rather than slept on, because Gradle and Kafka boot times are not a constant.
func TestWaitForMomentWaitsForTheDayToStartRatherThanForAGuess(t *testing.T) {
	log := filepath.Join(t.TempDir(), "run.log")
	if err := os.WriteFile(log, []byte("== Seeding ==\n  4387 accounts\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	r := &recorder{}
	s := settings(t, r)
	s.RunLog = log
	s.After = 20 * time.Second
	slept := []time.Duration{}
	polls := 0
	s.Sleep = func(_ context.Context, d time.Duration) error {
		slept = append(slept, d)
		// The day starts while the third poll is waiting.
		if polls++; polls == 3 {
			return os.WriteFile(log, []byte("== Seeding ==\n== Run ==\n  9h of business time\n"), 0o600)
		}
		return nil
	}
	m, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.WaitForMoment(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(slept) == 0 || slept[len(slept)-1] != 20*time.Second {
		t.Fatalf("did not wait the declared offset into the day after the marker: %v", slept)
	}
}

func TestWaitForMomentGivesUpRatherThanWaitingForever(t *testing.T) {
	log := filepath.Join(t.TempDir(), "run.log")
	if err := os.WriteFile(log, []byte("== Seeding ==\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	r := &recorder{}
	s := settings(t, r)
	s.RunLog = log
	s.WaitFor = 3 * time.Second
	s.Sleep = func(context.Context, time.Duration) error { return nil }
	s.Now = func() func() time.Time {
		at := time.Unix(0, 0)
		return func() time.Time {
			at = at.Add(time.Second)
			return at
		}
	}()
	m, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.WaitForMoment(context.Background()); err == nil {
		t.Fatal("waited for a day that never started without ever giving up")
	}
}
