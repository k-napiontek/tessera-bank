// workload-migration applies a schema migration to a live ledger while a day is being driven at it,
// and records the lock it took and what the customer experienced while it held it.
//
// It runs *beside* workload-run rather than instead of it. The driver executes the day; this waits
// for the day to actually start, waits the declared offset into it, and migrates while requests are
// in flight. A migration applied between two runs measures a maintenance window, which is the thing
// the exercise exists not to be.
//
//	go -C workload run ./cmd/workload-migration \
//	  --variant blocking --migrations migrations/blocking \
//	  --db-container tessera-dataset-db --run-log /tmp/tessera-workload-run.log \
//	  --edge-metrics http://localhost:9091/metrics \
//	  --ledger-metrics http://localhost:8080/actuator/prometheus \
//	  --out /tmp/migration-capture
//
// The two customer-side scrapes bracket the **migration**, not the run. The run's own brackets cover
// a whole compressed day and would dilute a lock held for seconds into an average nobody can read;
// what an operator needs to know is what the customer experienced while the lock was held.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/injector"
	"github.com/k-napiontek/tessera-bank/workload/internal/migration"
	"github.com/k-napiontek/tessera-bank/workload/internal/slo"
)

type options struct {
	variant       string
	migrations    string
	dbContainer   string
	runLog        string
	after         time.Duration
	waitFor       time.Duration
	table         string
	index         string
	historyTable  string
	edgeMetrics   string
	ledgerMetrics string
	catalogue     string
	out           string
	rollback      bool
	scrapeTimeout time.Duration
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "\nworkload-migration: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	opts := options{}
	flag.StringVar(&opts.variant, "variant", "blocking", "blocking or concurrent")
	flag.StringVar(&opts.migrations, "migrations", "", "directory holding the one migration to apply")
	flag.StringVar(&opts.dbContainer, "db-container", "tessera-workload-db", "the database container")
	flag.StringVar(&opts.runLog, "run-log", "", "the driver's own output, watched for the day to start")
	flag.DurationVar(&opts.after, "after", 20*time.Second, "how far into the day to migrate")
	flag.DurationVar(&opts.waitFor, "wait-for", 10*time.Minute, "how long to wait for the day to start")
	flag.StringVar(&opts.table, "table", "posting", "the table the migration touches")
	flag.StringVar(&opts.index, "index", "posting_exercise_ix", "the index it creates")
	flag.StringVar(&opts.historyTable, "history-table", "", "the exercise's own Flyway history table")
	flag.StringVar(&opts.edgeMetrics, "edge-metrics", "", "the gateway's admin endpoint")
	flag.StringVar(&opts.ledgerMetrics, "ledger-metrics", "", "the ledger's actuator endpoint")
	flag.StringVar(&opts.catalogue, "catalogue", "../contracts/slo/tessera-slo-v1.json",
		"the SLO catalogue the customer-side readings are judged against")
	flag.StringVar(&opts.out, "out", "", "directory the capture is written to")
	flag.BoolVar(&opts.rollback, "rollback", true, "drop the index and the history table afterwards")
	flag.DurationVar(&opts.scrapeTimeout, "scrape-timeout", 10*time.Second, "per-scrape timeout")
	flag.Parse()

	if opts.migrations == "" {
		return errors.New("--migrations is required")
	}
	if opts.out == "" {
		return errors.New("--out is required: a capture nobody wrote down is an anecdote")
	}
	if opts.historyTable == "" {
		opts.historyTable = "workload_exercise_" + opts.variant + "_history"
	}
	if err := os.MkdirAll(opts.out, 0o755); err != nil {
		return err
	}

	// Interrupting the exercise must still roll it back, or the next run finds the index already
	// there. The rollback therefore runs on a context of its own, exactly as internal/injector's
	// revert does.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	fixture := migration.Local{Local: &injector.Local{}}
	exercise, err := migration.New(migration.Settings{
		Fixture:           fixture,
		DatabaseContainer: opts.dbContainer,
		MigrationsDir:     opts.migrations,
		HistoryTable:      opts.historyTable,
		Variant:           migration.Variant(opts.variant),
		Table:             opts.table,
		Index:             opts.index,
		RunLog:            opts.runLog,
		After:             opts.after,
		WaitFor:           opts.waitFor,
		Log:               func(format string, args ...any) { fmt.Printf(format, args...) },
	})
	if err != nil {
		return err
	}

	statement, err := readStatement(opts.migrations)
	if err != nil {
		return err
	}

	fmt.Printf("== Migration under traffic ==\n  %s, %s on %s\n  %s\n\n",
		opts.variant, opts.index, opts.table, statement)

	fmt.Printf("  waiting for the day to start in %s\n", opts.runLog)
	if err := exercise.WaitForMoment(ctx); err != nil {
		return err
	}

	// The opening pair, taken as late as possible before the migration and as early as possible
	// after it, so the window they bracket is the migration and nothing else.
	if err := scrapeAll(ctx, opts, opts.out, "before"); err != nil {
		return err
	}

	record, applyErr := exercise.Apply(ctx)
	record.Statement = statement

	if scrapeErr := scrapeAll(ctx, opts, opts.out, "after"); scrapeErr != nil && applyErr == nil {
		applyErr = scrapeErr
	}

	// Written before the error is returned. A migration that failed is still a measurement, and the
	// lock samples up to the moment it failed are the most interesting ones it produced.
	if err := writeCapture(opts, record); err != nil {
		return err
	}

	if opts.rollback {
		if err := exercise.Rollback(context.WithoutCancel(ctx)); err != nil {
			fmt.Fprintf(os.Stderr, "  rollback failed: %v\n", err)
		}
	}
	if applyErr != nil {
		return applyErr
	}

	report(opts, record)
	return nil
}

// readStatement keeps the SQL that was applied in the capture, so the record says what was done
// rather than pointing at a file that may since have changed.
func readStatement(dir string) (string, error) {
	entries, err := filepath.Glob(filepath.Join(dir, "V*__*.sql"))
	if err != nil {
		return "", err
	}
	if len(entries) != 1 {
		return "", fmt.Errorf("%s holds %d migrations and this exercise applies exactly one, so that "+
			"the lock recorded belongs to a statement the capture names", dir, len(entries))
	}
	content, err := os.ReadFile(entries[0])
	if err != nil {
		return "", err
	}
	return trimComments(string(content)), nil
}

func writeCapture(opts options, record migration.Record) error {
	document, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(opts.out, "migration.json"), append(document, '\n'), 0o644); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(opts.out, "locks.txt"),
		[]byte(record.Locks.Render(migration.Variant(opts.variant), opts.table)), 0o644)
}

// scrapeAll takes the two endpoints at one instant. Named -migration so nothing confuses them with
// the run's own brackets, which cover a whole day.
func scrapeAll(ctx context.Context, opts options, dir, when string) error {
	for _, each := range []struct{ url, name string }{
		{opts.edgeMetrics, when + "-edge-migration.prom"},
		{opts.ledgerMetrics, when + "-ledger-migration.prom"},
	} {
		if each.url == "" {
			continue
		}
		body, err := scrape(ctx, each.url, opts.scrapeTimeout)
		if err != nil {
			return fmt.Errorf("scraping %s: %w", each.url, err)
		}
		if err := os.WriteFile(filepath.Join(dir, each.name), body, 0o644); err != nil {
			return err
		}
	}
	return nil
}

func scrape(ctx context.Context, url string, timeout time.Duration) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s answered %d", url, response.StatusCode)
	}
	return io.ReadAll(response.Body)
}

// report prints what the migration did, and what the customer saw while it did it.
//
// The customer-side figures come out of internal/slo against contracts/slo/, never out of a
// threshold chosen here: a second place where this estate says what good looks like is exactly what
// ADR 0012 exists to prevent.
func report(opts options, record migration.Record) {
	fmt.Printf("\n== What it did ==\n")
	// The two durations are printed together and labelled, because the difference between them is
	// the Flyway container's own startup and reporting either one alone would be misleading.
	fmt.Printf("  the whole Flyway invocation took   %s\n", record.Took.Round(time.Millisecond))
	fmt.Printf("  %s was held for about        %s (%d of %d samples)\n",
		record.Locks.OwnMode, record.Locks.HeldFor(), record.Locks.SamplesHolding, record.Locks.Samples)
	if record.Locks.SamplesHolding > 0 {
		fmt.Printf("    from %s to %s\n", record.Locks.HeldFrom, record.Locks.HeldTo)
	}
	fmt.Printf("  modes granted on %s   %v\n", record.Table, record.Locks.ModesGranted)
	fmt.Printf("  modes queued for %s   %v\n", record.Table, record.Locks.ModesQueued)
	fmt.Printf("  backends waiting, peak while held   %d\n", record.Locks.MaxWaitingWhileHeld)
	fmt.Printf("  backends waiting, peak overall      %d\n", record.Locks.MaxWaiting)

	readings, err := customerSide(opts)
	if err != nil {
		fmt.Printf("\n  the customer's side could not be read: %v\n", err)
		return
	}
	fmt.Printf("\n== What the customer saw, over the migration window only ==\n")
	for _, each := range readings {
		fmt.Printf("  %-32s %s\n", each.id, each.standing)
	}
}

type reading struct {
	id       string
	standing string
}

func customerSide(opts options) ([]reading, error) {
	if opts.edgeMetrics == "" {
		return nil, errors.New("no gateway endpoint was given")
	}
	document, err := os.ReadFile(opts.catalogue)
	if err != nil {
		return nil, err
	}
	catalogue, err := slo.Decode(document)
	if err != nil {
		return nil, err
	}
	before, err := os.ReadFile(filepath.Join(opts.out, "before-edge-migration.prom"))
	if err != nil {
		return nil, err
	}
	after, err := os.ReadFile(filepath.Join(opts.out, "after-edge-migration.prom"))
	if err != nil {
		return nil, err
	}

	out := []reading{}
	for _, component := range catalogue.Components {
		for _, objective := range component.Objectives {
			if objective.ObjectiveID != "SLO-GATEWAY-LATENCY" && objective.ObjectiveID != "SLO-GATEWAY-AVAILABILITY" {
				continue
			}
			result := slo.Evaluate(objective, string(before), string(after))
			out = append(out, reading{id: objective.ObjectiveID, standing: describe(result)})
		}
	}
	return out, nil
}

func describe(result slo.Reading) string {
	if !result.Computable {
		return "not computable from this pair: " + result.Why
	}
	verdict := "met"
	if !result.Met() {
		verdict = "MISSED"
	}
	return fmt.Sprintf("%.5f against %.5f - %s over %.0f requests",
		result.Achieved(), result.Objective.Target, verdict, result.Valid)
}

// trimComments keeps the statement rather than the file: the capture should say what was applied,
// and a reader should not have to strip the rationale out of it to see the SQL.
func trimComments(sql string) string {
	kept := []string{}
	for _, line := range strings.Split(sql, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "--") {
			continue
		}
		kept = append(kept, line)
	}
	return strings.Join(kept, " ")
}
