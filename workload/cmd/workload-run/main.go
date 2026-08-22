// Command workload-run executes a workload model against a running estate.
//
// It is the WP-21 half of the strand: WP-20's engine decides what a bank's day looks like and when
// every request should go out, and this sends them. **It is a test fixture, not a component of the
// bank** - it sits in the category walkthrough.sh and dev-token.mjs already occupy, and nothing in
// the estate depends on it.
//
//	go -C workload run ./cmd/workload-run \
//	  --model ../contracts/workload/tessera-day-v1.json \
//	  --date 2026-08-31 --seed 42 --scale 0.002 --compress 720 \
//	  --window branch-hours --gateway http://localhost:8081
//
// Everything it needs from the outside world is a flag, and everything it produces is on stdout, in
// a manifest, or on the metrics port. The engine underneath it reads no clock and opens no socket;
// internal/purity holds that boundary and names this directory as the exemption.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/arrivals"
	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/hardware"
	"github.com/k-napiontek/tessera-bank/workload/internal/identity"
	"github.com/k-napiontek/tessera-bank/workload/internal/injector"
	"github.com/k-napiontek/tessera-bank/workload/internal/manifest"
	"github.com/k-napiontek/tessera-bank/workload/internal/metrics"
	"github.com/k-napiontek/tessera-bank/workload/internal/model"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/proxy"
	"github.com/k-napiontek/tessera-bank/workload/internal/reconcile"
	"github.com/k-napiontek/tessera-bank/workload/internal/runner"
	"github.com/k-napiontek/tessera-bank/workload/internal/seeding"
)

// This file names float64, and internal/money/source_test.go requires a recorded reason. The reason
// is that the report prints rates per second and latency quantiles. It prints no amount at all: a
// run reports what it did, never what it moved.

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "workload-run: %v\n", err)
		os.Exit(1)
	}
}

type options struct {
	modelPath string
	date      string
	seed      uint64
	scale     float64
	compress  int
	window    string
	from, to  int

	gateway       string
	ledgerMetrics string
	edgeMetrics   string
	fraudMetrics  string
	proxyListen   string
	proxyUpstream string

	scenarioPath    string
	scenarioID      string
	runDir          string
	brokerContainer string
	dbContainer     string
	issuer          string
	audience        string
	keysPath        string
	metricsListen   string
	manifestPath    string
	scrapeDir       string

	timeout      time.Duration
	attempts     int
	expected     int
	seedWorkers  int
	waitFor      time.Duration
	skipSeeding  bool
	skipRun      bool
	tokenMinutes int
	settle       time.Duration
	drain        time.Duration
}

func run() error {
	opts := parse()
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
	from, to, err := window(loaded, opts)
	if err != nil {
		return err
	}

	// Started before anything is sent, because the gateway is pointed at it and will start looking
	// for it as soon as this process writes its public key. In path for every run including the
	// baseline: a signature taken through one hop and diffed against a normal taken through none
	// differs by more than the condition.
	condition, scenarioDigest, injecting, err := loadScenario(opts)
	if err != nil {
		return err
	}
	if injecting {
		fmt.Printf("== Scenario ==\n  %s - %s\n  %s\n\n",
			condition.ScenarioID, condition.Title, condition.Mechanism)
	}

	hop, err := startProxy(opts)
	if err != nil {
		return err
	}
	if hop != nil {
		defer func() { _ = hop.Close() }()
		fmt.Printf("== Proxy ==\n  %s in front of %s, adding nothing until a condition says so\n\n",
			hop.Addr(), hop.Upstream())
	}

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
	record, err := manifest.New(manifest.Run{
		Model: loaded, BusinessDate: date, Seed: opts.seed, Scale: opts.scale,
		Compression: opts.compress, From: from, To: to, GitSHA: gitSHA(opts.modelPath),
		Hardware: hardware.Describe(),
		// Both or neither: manifest.New refuses one without the other, because a run that recorded a
		// condition without saying which catalogue it came from could be diffed against a run of a
		// scenario somebody had since edited.
		ScenarioID: condition.ScenarioID, ScenarioDigest: scenarioDigest,
	})
	if err != nil {
		return err
	}

	held, err := seeding.BaseCurrency(people)
	if err != nil {
		return err
	}
	opening, err := seeding.Opening(people, held)
	if err != nil {
		return err
	}

	printHeader(record, opts, held)

	// The key pair is written before anything else, because the gateway is started against it: the
	// boot script waits for this file to appear. Only the public half is ever written down.
	issued, err := identity.Generate(identity.Settings{
		Issuer:   opts.issuer,
		Audience: opts.audience,
		Scopes:   identity.Scopes(),
		TTL:      time.Duration(opts.tokenMinutes) * time.Minute,
	})
	if err != nil {
		return err
	}
	if err := writeKeys(issued, opts.keysPath); err != nil {
		return err
	}
	fmt.Printf("  Keys            public key written to %s\n", opts.keysPath)

	registry := metrics.New()
	if opts.metricsListen != "" {
		stop := serveMetrics(registry, opts.metricsListen)
		defer stop()
		fmt.Printf("  Metrics         http://localhost%s/metrics\n\n", opts.metricsListen)
	}

	sender, err := client.New(client.Settings{
		Origin:   strings.TrimSuffix(opts.gateway, "/"),
		Timeout:  opts.timeout,
		Attempts: opts.attempts,
		Wallet:   identity.NewWallet(issued),
	})
	if err != nil {
		return err
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	if err := waitForGateway(ctx, opts.gateway, opts.waitFor); err != nil {
		return err
	}

	if !opts.skipSeeding {
		plan := seeding.Plan(people, inWindow(process, opts.seed, from, to), opts.seed, date)
		fmt.Printf("== Seeding ==\n  %d accounts, opening balance %s each, %d at a time\n",
			len(plan), opening, opts.seedWorkers)
		started := time.Now()
		report, err := seeding.Run(ctx, sender, plan, opening, opts.seedWorkers)
		if err != nil {
			return err
		}
		fmt.Printf("  opened %d, already open %d, funded %d, replayed %d, in %s\n\n",
			report.Opened, report.AlreadyOpen, report.Funded, report.Replayed,
			time.Since(started).Round(time.Millisecond))
	}
	if opts.skipRun {
		return writeManifest(record, opts.manifestPath)
	}

	before := scrapeLedger(opts.ledgerMetrics)
	// Written where the run brackets itself rather than from the shell around it. Seeding opens and
	// funds accounts through the same ledger, so a scrape taken before the process started would
	// fold the seeding into the baseline and report a run that moved more than it did.
	saveScrape(opts.scrapeDir, "before.prom", before.Body)
	// The edge at the same instant. A report built from the ledger alone says "nothing happened"
	// about every objective the gateway carries, which reads as an estate that did nothing rather
	// than as a report that looked in one place.
	saveScrape(opts.scrapeDir, "before-edge.prom", scrapeLedger(opts.edgeMetrics).Body)
	// And the scorer, which is only running when the fixture booted a broker for it. Left empty the
	// scrape is empty, and the report says "nothing happened" about fraud-scoring exactly as it did
	// before there was one to look at.
	saveScrape(opts.scrapeDir, "before-fraud.prom", scrapeLedger(opts.fraudMetrics).Body)

	fmt.Printf("== Run ==\n  %s of business time at %dx, so about %s of wall clock\n",
		businessSpan(from, to), opts.compress, record.RealDuration.Round(time.Second))

	// The condition runs beside the day rather than instead of it. A condition applied between two
	// runs measures a maintenance window, which is the thing this exercise exists not to be.
	var injection injector.Record
	var applying sync.WaitGroup
	var injectErr error
	if injecting {
		engine, err := newInjector(opts, hop, newStorm(sender, people, date, held))
		if err != nil {
			return err
		}
		applying.Add(1)
		go func() {
			defer applying.Done()
			injection, injectErr = engine.Run(ctx, condition, from)
		}()
	}

	summary, runErr := runner.Execute(ctx, runner.Settings{
		People: people, Process: process, Date: date, Seed: opts.seed,
		Compression: opts.compress, From: from, To: to, Held: held,
		Sender: sender, Observer: registry, Expected: opts.expected,
	})
	applying.Wait()
	if injectErr != nil && runErr == nil {
		runErr = injectErr
	}
	if injecting {
		printInjection(injection, condition)
	}

	// The scorer sits behind the outbox relay and the broker, so at the instant the last request is
	// answered it has consumed less than the run produced. Closing the bracket immediately would
	// report a scorer that fell behind when what happened is that nobody waited - a measurement of
	// the fixture rather than of the estate. The wait is stated rather than guessed, and the ledger
	// is scraped after it too, because the outbox lag at that moment is the one worth recording.
	if opts.settle > 0 {
		fmt.Printf("\n  settling for %s so the relay and the scorer can catch up\n", opts.settle)
		select {
		case <-time.After(opts.settle):
		case <-ctx.Done():
		}
	}
	if opts.drain > 0 {
		waitForOutbox(ctx, opts.ledgerMetrics, opts.drain)
	}

	after := scrapeLedger(opts.ledgerMetrics)
	saveScrape(opts.scrapeDir, "after.prom", after.Body)
	saveScrape(opts.scrapeDir, "after-edge.prom", scrapeLedger(opts.edgeMetrics).Body)
	saveScrape(opts.scrapeDir, "after-fraud.prom", scrapeLedger(opts.fraudMetrics).Body)
	printReport(summary, registry)
	printReconciliation(summary, before.Transfers, after.Transfers, opts.ledgerMetrics)

	if err := writeManifest(record, opts.manifestPath); err != nil {
		return err
	}
	if runErr != nil {
		return fmt.Errorf("the run was interrupted: %w", runErr)
	}
	return nil
}

func parse() options {
	var opts options
	flag.StringVar(&opts.modelPath, "model", "", "path to a workload model (required)")
	flag.StringVar(&opts.date, "date", "", "business date, YYYY-MM-DD (required)")
	flag.Uint64Var(&opts.seed, "seed", 42, "seed - the same seed and model give the same run")
	flag.Float64Var(&opts.scale, "scale", 0.001, "fraction of the model's real-time volume")
	flag.IntVar(&opts.compress, "compress", 720, "speed-up factor; 720 runs a 24h day in two minutes")
	flag.StringVar(&opts.window, "window", "", "restrict to a window the model names, e.g. branch-hours")
	flag.IntVar(&opts.from, "from", 0, "first minute of the day to run")
	flag.IntVar(&opts.to, "to", bankday.MinutesPerDay, "last minute of the day to run, exclusive")

	flag.StringVar(&opts.gateway, "gateway", "http://localhost:8081", "edge/api-gateway, scheme and authority")
	flag.StringVar(&opts.ledgerMetrics, "ledger-metrics", "http://localhost:8080/actuator/prometheus",
		"where to read ledger_transfers_total for the reconciliation; empty to skip it")
	flag.StringVar(&opts.issuer, "issuer", "https://issuer.tesserabank.example", "iss claim the gateway trusts")
	flag.StringVar(&opts.audience, "audience", "tessera-bank-ledger", "aud claim the gateway requires")
	flag.StringVar(&opts.keysPath, "keys", filepath.Join(os.TempDir(), "tessera-workload-keys.pem"),
		"where to write the public key the gateway verifies with")
	flag.StringVar(&opts.metricsListen, "metrics", ":9100", "address to serve tessera_workload_* on; empty to serve none")
	flag.StringVar(&opts.manifestPath, "manifest", "", "write the run manifest here, or - for stdout")

	flag.DurationVar(&opts.timeout, "timeout", 5*time.Second, "bound on one attempt")
	flag.IntVar(&opts.attempts, "attempts", 2, "attempts per logical request; only an unknown outcome is retried")
	flag.IntVar(&opts.expected, "expected", 64, "requests this run expects in flight at once - reported, never enforced")
	flag.IntVar(&opts.seedWorkers, "seed-workers", 8, "how many accounts to open at once before the run")
	flag.DurationVar(&opts.waitFor, "wait", 90*time.Second, "how long to wait for the gateway to answer")
	flag.StringVar(&opts.edgeMetrics, "edge-metrics", "",
		"the gateway's metrics endpoint, scraped at the same two instants as the ledger's")
	flag.StringVar(&opts.fraudMetrics, "fraud-metrics", "",
		"the scorer's metrics endpoint, scraped at the same two instants as the ledger's")
	flag.StringVar(&opts.proxyListen, "proxy-listen", "",
		"host a controllable hop on this address; the gateway is pointed at it instead of the ledger")
	flag.StringVar(&opts.proxyUpstream, "proxy-upstream", "",
		"what that hop forwards to - the ledger's own base address")
	flag.StringVar(&opts.scenarioPath, "scenario", "",
		"the failure-scenario catalogue to inject a condition from")
	flag.StringVar(&opts.scenarioID, "scenario-id", "",
		"which condition in that catalogue to inject during this run")
	flag.StringVar(&opts.runDir, "run-dir", "",
		"where the fixture wrote one <role>.pgid per process it started, so a condition can signal one")
	flag.StringVar(&opts.brokerContainer, "broker-container", "tessera-workload-kafka",
		"the broker container a condition may pause")
	flag.StringVar(&opts.dbContainer, "db-container", "tessera-workload-db",
		"the database container a condition may take a lock in")
	flag.DurationVar(&opts.settle, "settle", 0,
		"wait this long after the run before the closing scrapes, so that what the run handed to a "+
			"relay and a consumer has somewhere to arrive")
	flag.DurationVar(&opts.drain, "drain", 0,
		"then wait up to this long for the outbox to reach zero, so the closing scrape records an "+
			"estate that has caught up rather than one still working through the day")
	flag.StringVar(&opts.scrapeDir, "scrapes", "",
		"write the ledger scrapes that bracket the measured run into this directory")
	flag.BoolVar(&opts.skipSeeding, "skip-seeding", false, "assume the accounts are already open and funded")
	flag.BoolVar(&opts.skipRun, "seed-only", false, "seed the estate and stop")
	flag.IntVar(&opts.tokenMinutes, "token-ttl", 60, "minutes a minted token stays valid")
	flag.Parse()
	return opts
}

func window(loaded model.Model, opts options) (bankday.Minute, bankday.Minute, error) {
	from, to := bankday.Minute(opts.from), bankday.Minute(opts.to)
	if opts.window == "" {
		return from, to, nil
	}
	named, found := loaded.Window(opts.window)
	if !found {
		return 0, 0, fmt.Errorf("the model names no window %q", opts.window)
	}
	if named.WrapsMidnight() {
		return 0, 0, fmt.Errorf("%q wraps midnight, so it is two windows of one day - "+
			"pass --from and --to explicitly and say which half you mean", opts.window)
	}
	return named.Start, named.End, nil
}

// inWindow is the schedule seeding walks: the same events the run will send, and no others.
func inWindow(process arrivals.Process, seed uint64, from, to bankday.Minute) func(func(arrivals.Event) bool) {
	return func(yield func(arrivals.Event) bool) {
		for event := range process.Events(seed) {
			if event.Minute >= to {
				return
			}
			if event.Minute < from {
				continue
			}
			if !yield(event) {
				return
			}
		}
	}
}

func writeKeys(issued *identity.Issuer, path string) error {
	public, err := issued.PublicKeyPEM()
	if err != nil {
		return err
	}
	// 0644: it is a public key, and the gateway runs as whoever started it.
	return os.WriteFile(path, public, 0o644)
}

func serveMetrics(registry *metrics.Registry, address string) func() {
	mux := http.NewServeMux()
	mux.Handle("/metrics", registry.Handler())
	server := &http.Server{Addr: address, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() {
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			fmt.Fprintf(os.Stderr, "workload-run: the metrics port is not serving: %v\n", err)
		}
	}()
	return func() {
		shutdown, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}
}

// waitForGateway blocks until the gateway answers at all. Any status counts, 401 included: the
// question is whether the process is up, and this driver has no unauthenticated route to ask on.
func waitForGateway(ctx context.Context, origin string, limit time.Duration) error {
	if limit <= 0 {
		return nil
	}
	deadline := time.Now().Add(limit)
	probe := &http.Client{Timeout: 2 * time.Second}
	for {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, origin+"/accounts/TB00000000000000", nil)
		if err != nil {
			return err
		}
		response, err := probe.Do(request)
		if err == nil {
			_, _ = io.Copy(io.Discard, response.Body)
			response.Body.Close()
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("the gateway at %s did not answer within %s: %w", origin, limit, err)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// startProxy hosts the controllable hop, or nothing when the caller did not ask for one. Both flags
// or neither: a listen address with no upstream would answer every request with a 502, which reads
// as an estate that is down.
func startProxy(opts options) (*proxy.Proxy, error) {
	if opts.proxyListen == "" && opts.proxyUpstream == "" {
		return nil, nil
	}
	if opts.proxyListen == "" || opts.proxyUpstream == "" {
		return nil, fmt.Errorf("--proxy-listen and --proxy-upstream go together, and only %q is set",
			opts.proxyListen+opts.proxyUpstream)
	}
	return proxy.Start(opts.proxyListen, opts.proxyUpstream)
}

// waitForOutbox waits for the relay to publish everything the run produced, up to a bound.
//
// A fixed settle is a guess, and this run measures why that matters. Compression speeds the day up
// and the relay's tick does not move with it: the relay ships at most one batch per interval, both
// of which are the ledger's own configuration, so a day replayed at 720x hands it money movements
// far faster than a real day ever would. Closing the scrape bracket before it has caught up records
// a backlog that is an artefact of the dial rather than a property of the estate.
//
// It is a wait rather than an assertion. estate-up.sh checks afterwards whether the relay actually
// drained, and a run told to expect a backlog passes --drain 0 so that the backlog it exists to
// produce is still there when the scrape is taken.
func waitForOutbox(ctx context.Context, metricsURL string, bound time.Duration) {
	const pendingMetric = "ledger_outbox_pending"
	started := time.Now()
	deadline := started.Add(bound)
	first := -1.0

	for {
		pending := 0.0
		samples := reconcile.Read(scrapeLedger(metricsURL).Body, pendingMetric)
		if len(samples) == 0 {
			fmt.Printf("  the ledger publishes no %s, so the relay is not waited on\n", pendingMetric)
			return
		}
		for _, sample := range samples {
			pending += sample.Value
		}
		if first < 0 {
			first = pending
		}
		if pending == 0 {
			fmt.Printf("  the relay published the run's last %.0f events in %s\n",
				first, time.Since(started).Round(time.Second))
			return
		}
		if time.Now().After(deadline) || ctx.Err() != nil {
			fmt.Printf("  %.0f events still unpublished after %s, from %.0f when the day ended\n",
				pending, bound, first)
			return
		}
		select {
		case <-time.After(time.Second):
		case <-ctx.Done():
		}
	}
}

// scrape is one reading of the ledger: the counter the reconciliation needs, and the whole
// exposition, which is what WP-23's run report is generated from afterwards.
type scrape struct {
	Transfers reconcile.ByOutcome
	Body      string
}

func scrapeLedger(url string) scrape {
	if url == "" {
		return scrape{}
	}
	response, err := (&http.Client{Timeout: 5 * time.Second}).Get(url)
	if err != nil {
		return scrape{}
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 16<<20))
	if err != nil {
		return scrape{}
	}
	return scrape{Transfers: reconcile.Transfers(string(body)), Body: string(body)}
}

// saveScrape keeps one exposition beside the manifest, so that workload-report can generate the run
// report from files long after the terminal has gone.
func saveScrape(dir, name, body string) {
	if dir == "" || body == "" {
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "workload-run: cannot write scrapes to %s: %v\n", dir, err)
		return
	}
	if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
		fmt.Fprintf(os.Stderr, "workload-run: cannot write %s: %v\n", name, err)
	}
}

func printHeader(record manifest.Manifest, opts options, held money.Currency) {
	fmt.Printf("%s  %s  digest %s\n\n", record.ModelID, record.ModelVersion, record.ModelDigest[:12])
	fmt.Printf("  Business date   %s (%s)\n", record.BusinessDate, record.Weekday)
	fmt.Printf("  Window          %s to %s\n", record.Window.From, record.Window.To)
	fmt.Printf("  Seed            %d          commit %s\n", record.Seed, record.GitSHA)
	fmt.Printf("  Scale           %g          Compression %dx\n", record.Scale, record.Compression)
	fmt.Printf("  Events          %d\n", record.EventCount)
	fmt.Printf("  Offered rate    %.1f/s mean, %.1f/s peak     (what this run will produce)\n",
		record.Offered.MeanPerSecond, record.Offered.PeakPerSecond)
	fmt.Printf("  Gateway         %s\n", opts.gateway)
	fmt.Printf("  Currency        %s - every account is opened in it\n", held)
}

func printReport(summary runner.Summary, registry *metrics.Registry) {
	fmt.Printf("\n== Outcome ==\n")
	fmt.Printf("  scheduled %d, sent %d, elapsed %s\n",
		summary.Scheduled, summary.Sent, summary.Elapsed().Round(time.Millisecond))

	// Split, because a replay means something only where money moves: a balance enquiry answers 200
	// every time it works, and totalling the two together produces a replay rate that reads as
	// clients retrying a struggling ledger when nothing of the sort happened.
	fmt.Printf("  %-16s %s\n", "money movement", columns(moneyMoving(summary)))
	fmt.Printf("  %-16s %s\n", "reads", columns(reads(summary)))
	for reason, count := range summary.Unsent {
		fmt.Printf("  %-16s %d (%s)\n", "unsent", count, reason)
	}

	fmt.Printf("\n== Latency, from the intended send time ==\n")
	fmt.Printf("  mean %s   p50 %s   p95 %s   p99 %s   max %s\n",
		summary.MeanLatency().Round(time.Millisecond),
		registry.Quantile(0.5).Round(time.Millisecond),
		registry.Quantile(0.95).Round(time.Millisecond),
		registry.Quantile(0.99).Round(time.Millisecond),
		summary.LatencyMax.Round(time.Millisecond))

	fmt.Printf("\n== The driver itself ==\n")
	fmt.Printf("  peak in flight %d (expected %s), schedule lag max %s\n",
		summary.PeakInFlight, overExpected(summary), summary.MaxLag.Round(time.Millisecond))
	fmt.Printf("  retries %d, currency substituted %d, refused %d\n",
		summary.Retried, summary.Substituted, summary.Refused)
	if summary.MaxLag > time.Second {
		fmt.Printf("  NOTE  the scheduler fell more than a second behind its own schedule. Some of\n" +
			"        the latency above is this driver rather than the estate: lower --scale, or\n" +
			"        --compress, and run it again before reading the figures.\n")
	}
}

func overExpected(summary runner.Summary) string {
	if summary.OverExpected == 0 {
		return "never exceeded"
	}
	return fmt.Sprintf("exceeded %d times", summary.OverExpected)
}

func printReconciliation(summary runner.Summary, before, after reconcile.ByOutcome, url string) {
	fmt.Printf("\n== Reconciliation against the ledger's own count ==\n")
	if url == "" || before == nil || after == nil {
		fmt.Printf("  not attempted: %s was not readable, so this run is the only account of itself\n",
			describeURL(url))
		return
	}
	moved, usable := reconcile.Delta(before, after)
	if !usable {
		fmt.Printf("  not possible: the ledger's counters went backwards, so it restarted mid-run\n")
		return
	}

	rows := reconcile.Compare(moneyMoving(summary), moved)
	fmt.Printf("  %-10s %8s %8s   %s\n", "outcome", "driver", "ledger", "")
	for _, row := range rows {
		mark := "ok"
		if !row.Agrees() {
			mark = "DISAGREES"
		}
		note := row.Note
		if note != "" {
			note = "- " + note
		}
		fmt.Printf("  %-10s %8d %8d   %-9s %s\n", row.Outcome, row.Driver, row.Ledger, mark, note)
	}
	if !reconcile.Reconciled(rows) {
		fmt.Printf("  The two accounts of this run do not agree. That is a finding about the estate\n" +
			"  or about this driver, and it is worth more than the latency figures above.\n")
	}
}

func describeURL(url string) string {
	if url == "" {
		return "no ledger metrics endpoint was given"
	}
	return url
}

// moneyMoving is the driver's count over the operations the ledger's own counter records. Reads are
// left out: the ledger counts nothing for them, and including them would make every row disagree.
func moneyMoving(summary runner.Summary) map[string]int64 {
	counted := map[string]int64{}
	for _, operation := range []string{"createTransfer", "reverseTransfer", "placeHold", "captureHold", "releaseHold"} {
		for outcome, count := range summary.ByOperation[operation] {
			counted[outcome.String()] += count
		}
	}
	return counted
}

// columns renders one outcome row, leaving out the classes that did not occur so that the line a
// reader has to scan is short.
func columns(counted map[string]int64) string {
	var parts []string
	for _, outcome := range client.Outcomes() {
		count := counted[outcome.String()]
		if count == 0 {
			continue
		}
		parts = append(parts, fmt.Sprintf("%s %d", outcome, count))
	}
	if len(parts) == 0 {
		return "nothing"
	}
	return strings.Join(parts, "   ")
}

// reads is the driver's count over the operations that move no money. The ledger counts none of
// them, which is why they are reported apart rather than folded in.
func reads(summary runner.Summary) map[string]int64 {
	counted := map[string]int64{}
	for _, operation := range []string{"getAccount", "getBalance", "getStatement", "getTransfer", "listHolds"} {
		for outcome, count := range summary.ByOperation[operation] {
			counted[outcome.String()] += count
		}
	}
	return counted
}

func businessSpan(from, to bankday.Minute) string {
	return fmt.Sprintf("%dh%02dm", (to-from)/60, (to-from)%60)
}

func writeManifest(record manifest.Manifest, path string) error {
	if path == "" {
		return nil
	}
	rendered, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return err
	}
	rendered = append(rendered, '\n')
	if path == "-" {
		_, err := os.Stdout.Write(rendered)
		return err
	}
	return os.WriteFile(path, rendered, 0o644)
}

// gitSHA records which commit produced a run, so that a report found later can be read against the
// code that made it.
func gitSHA(near string) string {
	command := exec.Command("git", "-C", filepath.Dir(near), "rev-parse", "--short", "HEAD")
	out, err := command.Output()
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(out))
}
