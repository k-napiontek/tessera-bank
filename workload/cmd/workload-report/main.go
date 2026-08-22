// Command workload-report turns a run manifest and two metric scrapes into a report.
//
// workload-run prints its outcome as it finishes, and that report dies with the terminal. This one
// is generated afterwards from files, so a run can be read again months later, compared with the
// committed baseline, and regenerated to the byte by anybody holding the same inputs.
//
//	go -C workload run ./cmd/workload-report \
//	  --manifest run.json --before before.prom --after after.prom \
//	  --catalogue ../contracts/slo/tessera-slo-v1.json
//
// **It reads no clock and opens no socket.** A generation timestamp is what makes a byte-identical
// rerun impossible by construction, and batch/reporting already pays that price deliberately for the
// same reason. Everything the report says comes from the four inputs above; nothing is sampled now.
//
// It is a test fixture rather than a component of the bank, like everything else under workload/.
package main

import (
	"flag"
	"fmt"
	"io"
	"math"
	"os"
	"sort"
	"strings"

	"github.com/k-napiontek/tessera-bank/workload/internal/manifest"
	"github.com/k-napiontek/tessera-bank/workload/internal/scenario"
	"github.com/k-napiontek/tessera-bank/workload/internal/slo"
)

// This file names float64. Every figure printed here is a ratio, a rate or a count of events, and
// none of them is money: a report says what a run did, never what it moved.

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "workload-report: %v\n", err)
		os.Exit(1)
	}
}

// scrapes is a repeatable flag. The estate exposes its metrics in four different places - the
// ledger's actuator, the gateway's admin listener, and a textfile per batch job - and a report built
// from one of them says "nothing happened" about three quarters of the catalogue, which reads as an
// estate that did nothing rather than as a report that looked in one place.
type scrapes []string

func (s *scrapes) String() string { return strings.Join(*s, ",") }
func (s *scrapes) Set(value string) error {
	*s = append(*s, value)
	return nil
}

type options struct {
	manifestPath  string
	cataloguePath string
	before, after scrapes
	out           string

	scenarioPath                  string
	baselineBefore, baselineAfter scrapes
}

func run(args []string, stdout io.Writer) error {
	var opts options
	flags := flag.NewFlagSet("workload-report", flag.ContinueOnError)
	flags.SetOutput(stdout)
	flags.StringVar(&opts.manifestPath, "manifest", "", "the run manifest workload-run wrote")
	flags.StringVar(&opts.cataloguePath, "catalogue", "../contracts/slo/tessera-slo-v1.json",
		"the committed SLO catalogue")
	flags.Var(&opts.before, "before", "a scrape taken before the run; give it once per component")
	flags.Var(&opts.after, "after", "the matching scrape taken after it")
	flags.StringVar(&opts.out, "out", "-", "where to write the report, or - for stdout")
	flags.StringVar(&opts.scenarioPath, "scenario", "",
		"the failure-scenario catalogue, to judge this run against what its condition declared")
	flags.Var(&opts.baselineBefore, "baseline-before",
		"a scrape from the run this one is diffed against; give it once per component")
	flags.Var(&opts.baselineAfter, "baseline-after", "the matching closing scrape")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if opts.manifestPath == "" {
		return fmt.Errorf("--manifest is required")
	}
	if len(opts.before) == 0 || len(opts.after) == 0 {
		return fmt.Errorf("--before and --after are both required")
	}

	record, err := readManifest(opts.manifestPath)
	if err != nil {
		return err
	}
	document, err := os.ReadFile(opts.cataloguePath)
	if err != nil {
		return fmt.Errorf("reading the catalogue: %w", err)
	}
	catalogue, err := slo.Decode(document)
	if err != nil {
		return err
	}
	before, err := readAll(opts.before)
	if err != nil {
		return fmt.Errorf("reading the opening scrapes: %w", err)
	}
	after, err := readAll(opts.after)
	if err != nil {
		return fmt.Errorf("reading the closing scrapes: %w", err)
	}

	condition, baseline, err := readCondition(opts, record)
	if err != nil {
		return err
	}

	var page strings.Builder
	render(&page, record, catalogue, before, after)
	if condition != nil {
		renderSignature(&page, record, *condition, catalogue, capture{before, after}, baseline)
	}

	if opts.out == "-" {
		_, err := io.WriteString(stdout, page.String())
		return err
	}
	return os.WriteFile(opts.out, []byte(page.String()), 0o644)
}

// readAll joins several expositions into one. Metric names are unique across this estate - every one
// is prefixed with the component that emits it - so concatenating is exact rather than convenient.
func readAll(paths []string) (string, error) {
	var joined strings.Builder
	for _, path := range paths {
		body, err := os.ReadFile(path)
		if err != nil {
			return "", err
		}
		joined.Write(body)
		joined.WriteString("\n")
	}
	return joined.String(), nil
}

// readCondition resolves the scenario this run's manifest says it was executed under, and the
// baseline capture its signature is judged against.
//
// The identifier comes from the **manifest** rather than from a flag. A flag would let a capture be
// labelled with a condition it was not run under, and a mislabelled measurement is worse than a
// missing one - it is the only kind that gets quoted.
func readCondition(opts options, record manifest.Manifest) (*scenario.Scenario, capture, error) {
	if opts.scenarioPath == "" {
		if record.ScenarioID != "" {
			return nil, capture{}, fmt.Errorf(
				"this run was executed under %s and no --scenario was given, so its signature "+
					"cannot be judged; pass the catalogue or report a run that was not degraded",
				record.ScenarioID)
		}
		return nil, capture{}, nil
	}
	if record.ScenarioID == "" {
		return nil, capture{}, fmt.Errorf(
			"--scenario was given and this run was not executed under one")
	}
	if len(opts.baselineBefore) == 0 || len(opts.baselineAfter) == 0 {
		return nil, capture{}, fmt.Errorf(
			"--baseline-before and --baseline-after are required with --scenario: a degradation " +
				"described without the normal it degraded from is an anecdote")
	}

	document, err := os.ReadFile(opts.scenarioPath)
	if err != nil {
		return nil, capture{}, fmt.Errorf("reading the scenario catalogue: %w", err)
	}
	catalogue, err := scenario.Decode(document)
	if err != nil {
		return nil, capture{}, err
	}
	if catalogue.Digest() != record.ScenarioDigest {
		return nil, capture{}, fmt.Errorf(
			"the catalogue digest is %s and the run was executed under %s - this is a different "+
				"catalogue from the one that produced the run, so the signature it declares is not "+
				"the one that was asserted",
			short(catalogue.Digest()), short(record.ScenarioDigest))
	}
	chosen, err := catalogue.Find(record.ScenarioID)
	if err != nil {
		return nil, capture{}, err
	}

	before, err := readAll(opts.baselineBefore)
	if err != nil {
		return nil, capture{}, fmt.Errorf("reading the baseline's opening scrapes: %w", err)
	}
	after, err := readAll(opts.baselineAfter)
	if err != nil {
		return nil, capture{}, fmt.Errorf("reading the baseline's closing scrapes: %w", err)
	}
	return &chosen, capture{before, after}, nil
}

func readManifest(path string) (manifest.Manifest, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return manifest.Manifest{}, fmt.Errorf("reading the manifest: %w", err)
	}
	return manifest.Read(raw)
}

func render(out *strings.Builder, record manifest.Manifest, catalogue slo.Catalogue, before, after string) {
	fmt.Fprintf(out, "TESSERA BANK - RUN REPORT\n")
	fmt.Fprintf(out, "=========================\n\n")

	fmt.Fprintf(out, "Run\n")
	fmt.Fprintf(out, "  Model           %s %s  digest %s\n",
		record.ModelID, record.ModelVersion, short(record.ModelDigest))
	fmt.Fprintf(out, "  Business date   %s (%s)\n", record.BusinessDate, record.Weekday)
	fmt.Fprintf(out, "  Window          %s to %s\n", record.Window.From, record.Window.To)
	fmt.Fprintf(out, "  Seed            %d\n", record.Seed)
	fmt.Fprintf(out, "  Scale           %g    Compression %dx\n", record.Scale, record.Compression)
	fmt.Fprintf(out, "  Events          %d\n", record.EventCount)
	fmt.Fprintf(out, "  Offered rate    %.1f/s mean, %.1f/s peak\n",
		record.Offered.MeanPerSecond, record.Offered.PeakPerSecond)
	fmt.Fprintf(out, "  Commit          %s\n", record.GitSHA)
	fmt.Fprintf(out, "  Hardware        %s\n", record.Hardware)
	fmt.Fprintf(out, "\n  Catalogue       %s %s\n", catalogue.CatalogueID, catalogue.CatalogueVersion)

	var deferred []deferredReading

	fmt.Fprintf(out, "\nObjectives\n")
	for _, component := range catalogue.Components {
		fmt.Fprintf(out, "\n  %s (%s, stratum %s)\n",
			component.ComponentID, component.Path, component.Stratum)

		objectives := append([]slo.Objective(nil), component.Objectives...)
		// Sorted by id rather than left in document order: two catalogues that say the same thing in
		// a different order must produce the same report, or a reordering reads as a change.
		sort.Slice(objectives, func(i, j int) bool {
			return objectives[i].ObjectiveID < objectives[j].ObjectiveID
		})

		for _, objective := range objectives {
			reading := slo.Evaluate(objective, before, after)
			if !reading.Computable {
				deferred = append(deferred, deferredReading{component.ComponentID, reading})
				fmt.Fprintf(out, "    %-32s  %-9s  target %s   see below\n",
					objective.ObjectiveID, "no figure", ratio(objective.Target))
				continue
			}
			line := fmt.Sprintf("    %-32s  %-9s  target %s   %s   %s",
				objective.ObjectiveID,
				verdict(reading),
				ratio(objective.Target),
				achieved(reading),
				budget(reading))
			// Trailing space is an invisible difference, and this file is compared byte for byte
			// against a committed baseline.
			fmt.Fprintf(out, "%s\n", strings.TrimRight(line, " "))
		}
	}

	if len(deferred) > 0 {
		fmt.Fprintf(out, "\nObjectives this report cannot answer\n")
		fmt.Fprintf(out, "  Two scrapes are two points. These objectives are stated over a proportion\n")
		fmt.Fprintf(out, "  of a measurement window, and a figure produced from two samples would be\n")
		fmt.Fprintf(out, "  invented rather than measured.\n")
		for _, entry := range deferred {
			fmt.Fprintf(out, "\n    %s (%s)\n", entry.reading.Objective.ObjectiveID, entry.component)
			fmt.Fprintf(out, "      %s\n", entry.reading.Why)
			fmt.Fprintf(out, "      %s was %s at the start and %s at the end\n",
				entry.reading.Objective.SLI.ExposedName,
				number(entry.reading.ObservedBefore),
				number(entry.reading.ObservedAfter))
			if threshold := entry.reading.Objective.SLI.Threshold; threshold != nil {
				fmt.Fprintf(out, "      the objective wants it %s %g\n",
					entry.reading.Objective.SLI.Comparison, *threshold)
			}
		}
	}
}

type deferredReading struct {
	component string
	reading   slo.Reading
}

func verdict(reading slo.Reading) string {
	if reading.Valid == 0 {
		return "no events"
	}
	if reading.Met() {
		return "met"
	}
	return "MISSED"
}

func achieved(reading slo.Reading) string {
	if reading.Valid == 0 {
		return "nothing happened"
	}
	return fmt.Sprintf("%s of %s events", ratio(reading.Achieved()), count(reading.Valid))
}

func budget(reading slo.Reading) string {
	spent := reading.BudgetSpent()
	if math.IsNaN(spent) {
		return ""
	}
	if spent <= 0 {
		return "budget untouched"
	}
	return fmt.Sprintf("%.2fx budget", spent)
}

// ratio prints a proportion at a fixed width, so two reports diff on what changed rather than on
// how wide a number happened to be.
func ratio(value float64) string {
	if math.IsNaN(value) {
		return "    n/a"
	}
	return fmt.Sprintf("%.5f", value)
}

func count(value float64) string { return fmt.Sprintf("%.0f", value) }

func number(value float64) string {
	if math.IsNaN(value) {
		return "unknown"
	}
	return fmt.Sprintf("%g", value)
}

func short(digest string) string {
	if len(digest) <= 12 {
		return digest
	}
	return digest[:12]
}
