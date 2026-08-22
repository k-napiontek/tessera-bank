// Command workload-ceiling finds where the ledger stops going faster, and asks the database why.
//
// This is the measurement F-27 has been asking for since WP-09. The audit chain takes
// pg_advisory_xact_lock before reading the last hash and holds it until the transaction commits, so
// money-moving transactions cannot interleave: one writer at a time, service-wide. ADR 0005 states
// that ceiling rather than discovering it, and the follow-up says the redesign is "worth revisiting
// only with a measured number, not a hunch". This produces the number.
//
//	go -C workload run ./cmd/workload-ceiling \
//	  --ledger http://localhost:8080 --levels 1,2,4,8,16,32,64 --duration 10s
//
// Give --ledger twice to measure two instances sharing one database, which is the other half of the
// Definition of Done: a ceiling that does not move when a second writer is added is the answer, not
// a disappointment.
//
// # Why this drives the ledger directly
//
// The question is about a lock on the write path. Putting the gateway in front of it adds a rate
// limiter and a token check to a figure that is supposed to be about the database, and the limiter
// would cap the run long before the lock did.
//
// # Why this is a closed loop, when ADR 0016 says the model is open
//
// Not a contradiction: they measure different things. An open model fixes the send times in advance
// and measures latency at a demand the run did not get to choose, which is what stops coordinated
// omission from flattering the figures. A saturation point is the opposite question - "how much can
// this thing do at all" - and the instrument for that is a fixed number of workers each waiting for
// its answer before sending again, so the system is held exactly full and never more. Using an open
// model here would measure how fast the queue grew, which is a fact about the queue.
//
// # Why each worker gets its own pair of accounts
//
// So that what saturates is the chain lock and not the row locks. Workers sharing an account would
// contend on SELECT ... FOR UPDATE as well, and the run would find a ceiling without saying which of
// the two it had found - which is exactly the confusion the two separate timers exist to prevent.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/hardware"
	"github.com/k-napiontek/tessera-bank/workload/internal/reconcile"
)

// This file names float64. Every figure is a rate, a duration or a count of requests; the one amount
// it names is an int64 of minor units and nothing arithmetic happens to it.

const (
	transferMinor = 1
	currency      = "PLN"
)

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "workload-ceiling: %v\n", err)
		os.Exit(1)
	}
}

type origins []string

func (o *origins) String() string { return strings.Join(*o, ",") }
func (o *origins) Set(value string) error {
	*o = append(*o, strings.TrimRight(value, "/"))
	return nil
}

type options struct {
	ledgers  origins
	levels   string
	duration time.Duration
	timeout  time.Duration
	out      string
	prefix   string
	hardware string
	gitSHA   string
}

func run(args []string, stdout io.Writer) error {
	var opts options
	flags := flag.NewFlagSet("workload-ceiling", flag.ContinueOnError)
	flags.SetOutput(stdout)
	flags.Var(&opts.ledgers, "ledger", "a ledger origin; give it twice for two instances")
	flags.StringVar(&opts.levels, "levels", "1,2,4,8,16,32,64", "concurrency levels to walk")
	flags.DurationVar(&opts.duration, "duration", 10*time.Second, "how long to hold each level")
	flags.DurationVar(&opts.timeout, "timeout", 30*time.Second, "per-request timeout")
	flags.StringVar(&opts.out, "out", "", "write the measurement to this file as JSON")
	flags.StringVar(&opts.prefix, "prefix", "TB90", "account reference prefix for this run")
	flags.StringVar(&opts.hardware, "hardware", hardware.Describe(), "what this ran on")
	flags.StringVar(&opts.gitSHA, "git-sha", "unknown", "the commit the ledger was built from")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if len(opts.ledgers) == 0 {
		return fmt.Errorf("--ledger is required")
	}
	levels, err := parseLevels(opts.levels)
	if err != nil {
		return err
	}

	ctx := context.Background()
	bench := &bench{opts: opts, client: &http.Client{Timeout: opts.timeout}}

	fmt.Fprintf(stdout, "Ledger instances : %s\n", strings.Join(opts.ledgers, ", "))
	fmt.Fprintf(stdout, "Levels           : %v, %s each\n\n", levels, opts.duration)
	fmt.Fprintf(stdout, "%8s %10s %12s %12s %12s %12s\n",
		"workers", "posted", "per second", "mean ms", "chain ms", "account ms")

	measurement := Measurement{
		Conditions: Conditions{
			LedgerInstances:   len(opts.ledgers),
			Levels:            levels,
			SecondsPerLevel:   opts.duration.Seconds(),
			PoolMaxPerLedger:  bench.poolMax(),
			AccountsPerWorker: "two of its own, funded from a treasury opened for this run",
			Hardware:          opts.hardware,
			GitSHA:            opts.gitSHA,
		},
		Levels: []Level{},
	}

	for _, workers := range levels {
		level, err := bench.at(ctx, workers)
		if err != nil {
			return err
		}
		measurement.Levels = append(measurement.Levels, level)
		fmt.Fprintf(stdout, "%8d %10d %12.1f %12.2f %12.3f %12.3f\n",
			level.Workers, level.Posted, level.PerSecond, level.MeanMillis,
			level.ChainWaitMillis, level.AccountWaitMillis)
	}

	measurement.summarise()
	fmt.Fprintf(stdout, "\n%s\n", measurement.Conclusion)

	if opts.out != "" {
		rendered, err := json.MarshalIndent(measurement, "", "  ")
		if err != nil {
			return err
		}
		return os.WriteFile(opts.out, append(rendered, '\n'), 0o644)
	}
	return nil
}

func parseLevels(list string) ([]int, error) {
	var levels []int
	for _, field := range strings.Split(list, ",") {
		value, err := strconv.Atoi(strings.TrimSpace(field))
		if err != nil || value < 1 {
			return nil, fmt.Errorf("%q is not a concurrency level", field)
		}
		levels = append(levels, value)
	}
	sort.Ints(levels)
	return levels, nil
}

// Level is one concurrency level's result.
type Level struct {
	Workers           int     `json:"workers"`
	Posted            int64   `json:"posted"`
	Failed            int64   `json:"failed"`
	PerSecond         float64 `json:"perSecond"`
	MeanMillis        float64 `json:"meanMillis"`
	ChainWaitMillis   float64 `json:"chainWaitMillisPerPosting"`
	AccountWaitMillis float64 `json:"accountWaitMillisPerPosting"`
}

// Conditions is what the figures were taken under.
//
// A number without them is another hunch wearing a decimal point - F-27 asks for a measured figure
// and the work package requires the conditions stated with it, because two measurements that do not
// say what they ran on cannot be compared, and comparing them anyway is how a team concludes a
// regression exists.
type Conditions struct {
	LedgerInstances   int     `json:"ledgerInstances"`
	Levels            []int   `json:"levels"`
	SecondsPerLevel   float64 `json:"secondsPerLevel"`
	PoolMaxPerLedger  float64 `json:"poolMaxPerLedger"`
	AccountsPerWorker string  `json:"accountsPerWorker"`
	Hardware          string  `json:"hardware"`
	GitSHA            string  `json:"gitSha"`
}

// Measurement is the whole run, in the form the write-up quotes.
type Measurement struct {
	Conditions Conditions `json:"conditions"`
	Levels     []Level    `json:"levels"`
	PeakPerSec float64    `json:"peakPerSecond"`
	PeakAt     int        `json:"peakAtWorkers"`
	Conclusion string     `json:"conclusion"`
}

func (m *Measurement) summarise() {
	for _, level := range m.Levels {
		if level.PerSecond > m.PeakPerSec {
			m.PeakPerSec, m.PeakAt = level.PerSecond, level.Workers
		}
	}
	m.Conclusion = fmt.Sprintf(
		"Peak %.1f postings a second at %d workers, across %d ledger instance(s). "+
			"Adding workers past that point buys latency, not throughput.",
		m.PeakPerSec, m.PeakAt, m.Conditions.LedgerInstances)
}

type bench struct {
	opts   options
	client *http.Client
	seq    atomic.Int64
}

// at holds one concurrency level for the configured duration and reports what it managed.
func (b *bench) at(ctx context.Context, workers int) (Level, error) {
	pairs, err := b.openPairs(ctx, workers)
	if err != nil {
		return Level{}, err
	}

	before := b.scrapeAll()

	var posted, failed atomic.Int64
	var nanos atomic.Int64

	deadline := time.Now().Add(b.opts.duration)
	var group sync.WaitGroup
	started := time.Now()

	for worker := 0; worker < workers; worker++ {
		group.Add(1)
		go func(worker int) {
			defer group.Done()
			pair := pairs[worker]
			origin := b.opts.ledgers[worker%len(b.opts.ledgers)]
			for time.Now().Before(deadline) {
				at := time.Now()
				if err := b.transfer(ctx, origin, pair); err != nil {
					failed.Add(1)
					continue
				}
				nanos.Add(int64(time.Since(at)))
				posted.Add(1)
			}
		}(worker)
	}
	group.Wait()
	elapsed := time.Since(started)

	after := b.scrapeAll()
	done := posted.Load()

	level := Level{
		Workers: workers,
		Posted:  done,
		Failed:  failed.Load(),
	}
	if elapsed > 0 {
		level.PerSecond = float64(done) / elapsed.Seconds()
	}
	if done > 0 {
		level.MeanMillis = float64(nanos.Load()) / float64(done) / 1e6
		level.ChainWaitMillis = waitPerPosting(before, after, "ledger_lock_chain_seconds", done)
		level.AccountWaitMillis = waitPerPosting(before, after, "ledger_lock_account_seconds", done)
	}
	return level, nil
}

// waitPerPosting is the total time spent in one lock over the level, divided by what the level
// posted - so the two lock columns are directly comparable with the mean latency beside them.
func waitPerPosting(before, after map[string]float64, metric string, posted int64) float64 {
	moved := after[metric+"_sum"] - before[metric+"_sum"]
	if moved <= 0 || posted == 0 {
		return 0
	}
	return moved / float64(posted) * 1000
}

// poolMax reads the connection pool's ceiling out of one instance's scrape, so the figure a reader
// needs in order to interpret the concurrency ladder comes from the ledger rather than from a note.
func (b *bench) poolMax() float64 {
	body, err := b.get(b.opts.ledgers[0] + "/actuator/prometheus")
	if err != nil {
		return math.NaN()
	}
	for _, sample := range reconcile.Read(body, "hikaricp_connections_max") {
		return sample.Value
	}
	return math.NaN()
}

// scrapeAll totals the lock timers across every instance. Two ledgers queue on the same advisory
// lock, so the sum across them is the figure that matters rather than either one alone.
func (b *bench) scrapeAll() map[string]float64 {
	totals := map[string]float64{}
	for _, origin := range b.opts.ledgers {
		body, err := b.get(origin + "/actuator/prometheus")
		if err != nil {
			continue
		}
		for _, metric := range []string{"ledger_lock_chain_seconds", "ledger_lock_account_seconds"} {
			for _, suffix := range []string{"_sum", "_count"} {
				for _, sample := range reconcile.Read(body, metric+suffix) {
					if math.IsNaN(sample.Value) {
						continue
					}
					totals[metric+suffix] += sample.Value
				}
			}
		}
	}
	return totals
}

func (b *bench) get(url string) (string, error) {
	response, err := b.client.Get(url)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 8<<20))
	return string(body), err
}

type pair struct{ debit, credit string }

// openPairs gives every worker two accounts of its own, funded from a treasury opened for this run.
func (b *bench) openPairs(ctx context.Context, workers int) ([]pair, error) {
	origin := b.opts.ledgers[0]
	treasury := b.reference()
	if err := b.openAccount(ctx, origin, treasury, "ASSET"); err != nil {
		return nil, fmt.Errorf("opening the treasury: %w", err)
	}

	pairs := make([]pair, workers)
	for worker := range pairs {
		debit, credit := b.reference(), b.reference()
		if err := b.openAccount(ctx, origin, debit, "LIABILITY"); err != nil {
			return nil, err
		}
		if err := b.openAccount(ctx, origin, credit, "LIABILITY"); err != nil {
			return nil, err
		}
		// Funded generously: an overdraft refusal partway through a level would end the level early
		// and report a ceiling that was the balance rather than the lock.
		if err := b.post(ctx, origin, "/v1/transfers", transferBody(treasury, debit, 1_000_000_00)); err != nil {
			return nil, fmt.Errorf("funding %s: %w", debit, err)
		}
		pairs[worker] = pair{debit: debit, credit: credit}
	}
	return pairs, nil
}

func (b *bench) reference() string {
	return fmt.Sprintf("%s%012d", b.opts.prefix, b.seq.Add(1))
}

func (b *bench) openAccount(ctx context.Context, origin, reference, kind string) error {
	body := fmt.Sprintf(
		`{"accountRef":%q,"customerRef":"CU0000000001","accountType":%q,"currency":%q}`,
		reference, kind, currency)
	return b.post(ctx, origin, "/v1/accounts", body)
}

func (b *bench) transfer(ctx context.Context, origin string, p pair) error {
	return b.post(ctx, origin, "/v1/transfers", transferBody(p.debit, p.credit, transferMinor))
}

func transferBody(debit, credit string, minor int64) string {
	return fmt.Sprintf(
		`{"debitAccountRef":%q,"creditAccountRef":%q,"amount":{"amountMinor":%d,"currency":%q}}`,
		debit, credit, minor, currency)
}

func (b *bench) post(ctx context.Context, origin, path, body string) error {
	request, err := http.NewRequestWithContext(
		ctx, http.MethodPost, origin+path, bytes.NewReader([]byte(body)))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	// A fresh key per request. This harness is measuring how much work the ledger can do, so every
	// request must be work: a reused key would be answered from the idempotency store without ever
	// reaching the chain lock, and the ceiling would come out as high as the store is fast.
	request.Header.Set("Idempotency-Key", fmt.Sprintf("ceiling-%d-%d", time.Now().UnixNano(), b.seq.Add(1)))

	response, err := b.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	payload, _ := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if response.StatusCode >= 300 {
		return fmt.Errorf("%s answered %d: %s", path, response.StatusCode, strings.TrimSpace(string(payload)))
	}
	return nil
}
