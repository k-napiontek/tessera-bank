// Command workload-plan prints a workload model as a bank's day, and writes the manifest for a run.
//
// It drives nothing. WP-20 builds the model and the engine; WP-21 executes the schedule against the
// modern spine and WP-25 against the older strata. This tool exists because a curve that is only
// tested is a curve nobody has read - the hour-by-hour shape below is how a reader sees that this is
// a bank's day rather than a flat rate with a nice name.
//
// It is also the only part of this module allowed to touch the outside world. internal/purity
// enforces that boundary over everything else, and names this directory as the exemption.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/arrivals"
	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/manifest"
	"github.com/k-napiontek/tessera-bank/workload/internal/model"
)

// This file names float64, and internal/money/source_test.go requires a recorded reason. The reason
// is that the summary prints rates per second, which are not money. The one money figure it prints
// is an amount in minor units, and it comes out of the engine as an int64.

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "workload-plan: %v\n", err)
		os.Exit(1)
	}
}

type options struct {
	modelPath    string
	date         string
	seed         uint64
	scale        float64
	compress     int
	window       string
	from, to     int
	summary      bool
	events       int
	manifestPath string
}

func run() error {
	var opts options
	flag.StringVar(&opts.modelPath, "model", "", "path to a workload model (required)")
	flag.StringVar(&opts.date, "date", "", "business date, YYYY-MM-DD (required)")
	flag.Uint64Var(&opts.seed, "seed", 42, "seed - the same seed and model give the same schedule")
	flag.Float64Var(&opts.scale, "scale", 1.0, "fraction of the model's real-time volume")
	flag.IntVar(&opts.compress, "compress", 1, "speed-up factor; 72 runs a 24h day in 20 minutes")
	flag.StringVar(&opts.window, "window", "", "restrict to a window the model names, e.g. branch-hours")
	flag.IntVar(&opts.from, "from", 0, "first minute of the day to plan")
	flag.IntVar(&opts.to, "to", bankday.MinutesPerDay, "last minute of the day to plan, exclusive")
	flag.BoolVar(&opts.summary, "summary", false, "print the day hour by hour")
	flag.IntVar(&opts.events, "events", 0, "print this many scheduled events")
	flag.StringVar(&opts.manifestPath, "manifest", "", "write the run manifest here, or - for stdout")
	flag.Parse()

	if opts.modelPath == "" || opts.date == "" {
		flag.Usage()
		return fmt.Errorf("--model and --date are both required")
	}

	document, err := os.ReadFile(opts.modelPath)
	if err != nil {
		return err
	}
	loaded, err := model.Decode(document)
	if err != nil {
		return err
	}
	date, err := bankday.ParseDate(opts.date)
	if err != nil {
		return err
	}

	from, to := bankday.Minute(opts.from), bankday.Minute(opts.to)
	if opts.window != "" {
		named, found := loaded.Window(opts.window)
		if !found {
			return fmt.Errorf("the model names no window %q", opts.window)
		}
		if named.WrapsMidnight() {
			return fmt.Errorf("%q wraps midnight, so it is two windows of one day - "+
				"pass --from and --to explicitly and say which half you mean", opts.window)
		}
		from, to = named.Start, named.End
	}

	record, err := manifest.New(manifest.Run{
		Model:        loaded,
		BusinessDate: date,
		Seed:         opts.seed,
		Scale:        opts.scale,
		Compression:  opts.compress,
		From:         from,
		To:           to,
		GitSHA:       gitSHA(opts.modelPath),
		// This tool measures nothing - it prints what a model asks for. Naming the laptop it was
		// printed on would put a condition on a figure that has none.
		Hardware: "unrecorded",
	})
	if err != nil {
		return err
	}

	printHeader(loaded, record, opts.modelPath)
	if opts.summary {
		if err := printDay(loaded, record, date, opts.scale); err != nil {
			return err
		}
	}
	if opts.events > 0 {
		if err := printEvents(loaded, date, opts, from, to); err != nil {
			return err
		}
	}
	return writeManifest(record, opts.manifestPath)
}

func printHeader(loaded model.Model, record manifest.Manifest, path string) {
	fmt.Printf("%s  %s  digest %s\n", record.ModelID, record.ModelVersion, record.ModelDigest[:12])
	fmt.Printf("%s\n\n", wrap(loaded.Summary, 92))

	fmt.Printf("  Business date   %s (%s)\n", record.BusinessDate, record.Weekday)
	fmt.Printf("  Calendar        day multiplier %.4f\n", record.DayMultiplier)
	fmt.Printf("  Population      %s customers, %d accounts each, %d cohorts\n",
		thousands(int64(loaded.Population.Size)), loaded.Population.AccountsPerCustomer,
		len(loaded.Population.Cohorts))
	fmt.Printf("  Window          %s to %s\n", record.Window.From, record.Window.To)
	fmt.Printf("  Seed            %d          commit %s\n", record.Seed, record.GitSHA)
	fmt.Printf("  Scale           %g          Compression %dx, so the window runs in %s\n\n",
		record.Scale, record.Compression, record.RealDuration.Round(time.Second))

	fmt.Printf("  Events          %s\n", thousands(record.EventCount))
	fmt.Printf("  Business rate   %s/s mean, %s/s peak     (the model's own clock)\n",
		rate(record.Business.MeanPerSecond), rate(record.Business.PeakPerSecond))
	fmt.Printf("  Offered rate    %s/s mean, %s/s peak     (what a driver must produce)\n\n",
		rate(record.Offered.MeanPerSecond), rate(record.Offered.PeakPerSecond))

	// Compression multiplies intensity, and the two dials together produce numbers that look like
	// a claim about this estate. They are not: they are what the model asks for. Saying so here is
	// cheaper than a run report nobody can defend, and it is the Constraint WP-20 is explicit about.
	if record.Offered.PeakPerSecond > plausiblePeak {
		fmt.Printf("  NOTE  this asks for %s requests a second at peak. Nothing here has been measured\n"+
			"        serving more than about %s a second - see workload/baselines - so that is the\n"+
			"        model describing a real bank rather than a claim about Tessera: lower --scale\n"+
			"        to offer a fraction of it, or --compress to spread it out.\n\n",
			rate(record.Offered.PeakPerSecond), rate(plausiblePeak))
	}
}

// plausiblePeak is the rate above which the note above fires, and it is now a measured figure.
//
// It was 2 000 - a round number, named in this comment as standing in for something nobody had, which
// F-69 recorded. WP-23 measured it twice, on the same developer machine, and both answers landed in
// the same place:
//
//   - workload/baselines/ceiling-*.json walks a concurrency ladder against the ledger directly and
//     finds money-moving throughput peaking at about 790 postings a second, flat thereafter;
//   - workload/baselines/baseline-report.txt is a compressed bank day through the gateway that
//     sustained 34 323 requests in 45 seconds - about 760 a second - with every objective met.
//
// 800 is where those two agree. It is a property of the machine rather than of the design, which is
// why the note it fires says "nothing here has been measured serving more than this" rather than
// naming a limit; re-measure with scripts/ceiling.sh on anything else.
const plausiblePeak = 800

func printDay(loaded model.Model, record manifest.Manifest, date bankday.Date, scale float64) error {
	curve, err := loaded.Curve()
	if err != nil {
		return err
	}

	marks := map[int][]string{}
	for _, window := range loaded.Windows() {
		marks[window.Start.Hour()] = append(marks[window.Start.Hour()], window.ID+" opens")
		marks[window.End.Hour()] = append(marks[window.End.Hour()], window.ID+" closes")
	}
	for _, instant := range loaded.Instants() {
		marks[instant.At.Hour()] = append(marks[instant.At.Hour()], instant.ID)
	}

	peak := record.Offered.PeakPerSecond
	fmt.Println("  Hour   shape     offered/s")
	for hour := 0; hour < 24; hour++ {
		minute := bankday.Minute(hour * 60)
		offered := curve.RatePerSecond(date, minute, scale) * float64(record.Compression)
		bars := int(offered / peak * 46)
		fmt.Printf("  %02d:00  %5.2f  %11s  %s\n",
			hour, curve.HourShape(hour), rate(offered),
			strings.TrimRight(strings.Repeat("#", bars)+" "+strings.Join(marks[hour], ", "), " "))
	}
	fmt.Println()
	return nil
}

func printEvents(loaded model.Model, date bankday.Date, opts options, from, to bankday.Minute) error {
	curve, err := loaded.Curve()
	if err != nil {
		return err
	}
	people, err := loaded.People()
	if err != nil {
		return err
	}
	process, err := arrivals.New(curve, date, opts.scale)
	if err != nil {
		return err
	}

	fmt.Printf("  First %d scheduled events\n", opts.events)
	fmt.Printf("  %-6s %-16s %-15s %-14s %-17s %-17s %s\n",
		"at", "cohort", "operation", "customer", "account", "counterparty", "amount")

	shown := 0
	for event := range process.Events(opts.seed) {
		if event.Minute < from || event.Minute >= to {
			continue
		}
		if shown >= opts.events {
			break
		}
		action := people.Draw(opts.seed, event.Seq, date)
		amount := ""
		if action.MovesMoney() {
			amount = action.Amount.String()
		}
		fmt.Printf("  %-6s %-16s %-15s %-14s %-17s %-17s %s\n",
			event.Minute, action.Cohort, action.Operation, action.CustomerRef,
			action.AccountRef, action.CounterpartyRef, amount)
		shown++
	}
	fmt.Println()
	return nil
}

func writeManifest(record manifest.Manifest, path string) error {
	if path == "" {
		return nil
	}
	encoded, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return err
	}
	encoded = append(encoded, '\n')
	if path == "-" {
		_, err := os.Stdout.Write(encoded)
		return err
	}
	return os.WriteFile(path, encoded, 0o644)
}

// gitSHA reads the commit from .git, walking up from the model's directory.
//
// Read rather than shelled out for. os/exec is on internal/purity's forbidden list for the engine,
// and reaching for it here would put a subprocess in a tool whose entire job is to be reproducible.
// Answers "unknown" plainly rather than an empty string, because a manifest field that is sometimes
// blank and sometimes a commit is one a report has to guess about.
func gitSHA(near string) string {
	dir, err := filepath.Abs(filepath.Dir(near))
	if err != nil {
		return "unknown"
	}
	for {
		head, err := os.ReadFile(filepath.Join(dir, ".git", "HEAD"))
		if err == nil {
			text := strings.TrimSpace(string(head))
			if ref, found := strings.CutPrefix(text, "ref: "); found {
				resolved, err := os.ReadFile(filepath.Join(dir, ".git", filepath.FromSlash(ref)))
				if err != nil {
					return "unknown"
				}
				return strings.TrimSpace(string(resolved))
			}
			return text
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "unknown"
		}
		dir = parent
	}
}

func rate(perSecond float64) string {
	if perSecond >= 100 {
		return thousands(int64(perSecond + 0.5))
	}
	return fmt.Sprintf("%.2f", perSecond)
}

func thousands(n int64) string {
	text := fmt.Sprintf("%d", n)
	var out []byte
	for i, digit := range []byte(text) {
		if i > 0 && (len(text)-i)%3 == 0 {
			out = append(out, ' ')
		}
		out = append(out, digit)
	}
	return string(out)
}

func wrap(text string, width int) string {
	var out strings.Builder
	line := 0
	for _, word := range strings.Fields(text) {
		if line > 0 && line+len(word)+1 > width {
			out.WriteString("\n")
			line = 0
		} else if line > 0 {
			out.WriteString(" ")
			line++
		}
		out.WriteString(word)
		line += len(word)
	}
	return out.String()
}
