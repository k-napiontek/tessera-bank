// workload-soak turns a series of daily captures into what the ledger's tables did over time.
//
// It reads rather than drives: workload/scripts/soak.sh runs the days, and this makes the report
// from what they left behind. The same separation workload-report already has, and for the same
// reason - a report generated from committed files can be regenerated months later and checked,
// while one printed by the run that produced it dies with the terminal.
//
//	go -C workload run ./cmd/workload-soak --capture baselines/soak --out baselines/soak/report.txt
//
// The capture directory holds one day-NN/ per business date, each with the ledger's closing scrape
// and the run's own manifest. The days are read in business-date order rather than in the order the
// filesystem returns them, because a growth rate computed over shuffled points is not a growth rate.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"

	"github.com/k-napiontek/tessera-bank/workload/internal/manifest"
	"github.com/k-napiontek/tessera-bank/workload/internal/reconcile"
	"github.com/k-napiontek/tessera-bank/workload/internal/soak"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "\nworkload-soak: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	var capture, out string
	flag.StringVar(&capture, "capture", "", "the soak capture directory, holding one day-NN/ per date")
	flag.StringVar(&out, "out", "-", "where to write the report, or - for stdout")
	flag.Parse()

	if capture == "" {
		return errors.New("--capture is required")
	}

	days, conditions, err := read(capture)
	if err != nil {
		return err
	}
	growth, err := soak.Measure(days)
	if err != nil {
		return err
	}

	report := growth.Render(conditions)
	if out == "-" {
		fmt.Print(report)
		return nil
	}
	return os.WriteFile(out, []byte(report), 0o644)
}

// read gathers the days, in business-date order.
func read(capture string) ([]soak.Day, soak.Conditions, error) {
	entries, err := filepath.Glob(filepath.Join(capture, "day-*"))
	if err != nil {
		return nil, soak.Conditions{}, err
	}
	if len(entries) == 0 {
		return nil, soak.Conditions{}, fmt.Errorf("%s holds no day-NN directories", capture)
	}
	sort.Strings(entries)

	var days []soak.Day
	var conditions soak.Conditions
	for _, dir := range entries {
		scrape, err := os.ReadFile(filepath.Join(dir, "after.prom"))
		if err != nil {
			return nil, soak.Conditions{}, fmt.Errorf("%s: %w", dir, err)
		}
		record, err := readManifest(dir)
		if err != nil {
			return nil, soak.Conditions{}, err
		}

		// The postings are the ledger's own count of what this run moved, taken as the difference
		// between the run's two scrapes - the same counter and the same arithmetic the run's own
		// reconciliation uses, rather than the driver's account of what it sent. Two independent
		// counts of the same events are what makes either worth reading.
		before, err := os.ReadFile(filepath.Join(dir, "before.prom"))
		if err != nil {
			return nil, soak.Conditions{}, fmt.Errorf("%s: %w", dir, err)
		}
		postings := postedBetween(string(before), string(scrape))

		days = append(days, soak.Day{
			BusinessDate: record.BusinessDate,
			Scrape:       string(scrape),
			Postings:     postings,
		})

		// The conditions come from the last manifest read. Every day of a soak is driven at the same
		// dials by soak.sh, and a run that was not would be a soak of two different things.
		conditions = soak.Conditions{
			Scale:       record.Scale,
			Compression: record.Compression,
			Window:      record.Window.From + " to " + record.Window.To,
			Hardware:    record.Hardware,
			GitSHA:      record.GitSHA,
		}
	}

	if accounts, err := datasetAccounts(capture); err == nil {
		conditions.Accounts = accounts
	}

	// A soak whose days were driven at different dials is not a soak. Reported rather than silently
	// averaged, because averaging them would produce a rate describing no run that ever happened.
	if err := sameDials(entries); err != nil {
		return nil, soak.Conditions{}, err
	}
	return days, conditions, nil
}

// postedBetween is the ledger's own count of what moved during one run.
func postedBetween(before, after string) float64 {
	moved, ok := reconcile.Delta(reconcile.Transfers(before), reconcile.Transfers(after))
	if !ok {
		return 0
	}
	return moved["posted"]
}

// readManifest reads one day's run record. manifest.Read takes bytes rather than a path, for the
// reason internal/model's Decode does: the things that load one load it from different places.
func readManifest(dir string) (manifest.Manifest, error) {
	document, err := os.ReadFile(filepath.Join(dir, "run-manifest.json"))
	if err != nil {
		return manifest.Manifest{}, fmt.Errorf("%s: %w", dir, err)
	}
	record, err := manifest.Read(document)
	if err != nil {
		return manifest.Manifest{}, fmt.Errorf("%s: %w", dir, err)
	}
	return record, nil
}

func sameDials(dirs []string) error {
	var scale float64
	var compression int
	for i, dir := range dirs {
		record, err := readManifest(dir)
		if err != nil {
			return err
		}
		if i == 0 {
			scale, compression = record.Scale, record.Compression
			continue
		}
		if record.Scale != scale || record.Compression != compression {
			return fmt.Errorf("%s was driven at scale %g and %dx while the first day was driven at "+
				"scale %g and %dx - a growth rate over two different dials describes no run that "+
				"happened", dir, record.Scale, record.Compression, scale, compression)
		}
	}
	return nil
}

// datasetAccounts reads how many accounts the soak ran against, so the report can say what the
// churn was measured over. Absent is absent: the field is left at zero rather than guessed.
func datasetAccounts(capture string) (float64, error) {
	content, err := os.ReadFile(filepath.Join(capture, "dataset-manifest.json"))
	if err != nil {
		return 0, err
	}
	var document map[string]any
	if err := json.Unmarshal(content, &document); err != nil {
		return 0, err
	}
	for _, key := range []string{"accounts", "accountsWritten", "accountCount"} {
		if value, ok := document[key].(float64); ok {
			return value, nil
		}
	}
	return 0, errors.New("no account count in the dataset manifest")
}
