// Command workload-soap finds where legacy/customer-master stops going faster, and says which of
// its two pools ran out first.
//
//	go -C workload run ./cmd/workload-soap \
//	  --endpoint http://localhost:18080/customer-master/services/CustomerMasterService \
//	  --accounts /tmp/tessera-legacy/accounts.txt \
//	  --levels 1,2,4,8,16,32,64 --duration 10s
//
// # Why a ladder rather than the day
//
// The question WP-25's Objective asks about this tier is *"a SOAP endpoint whose thread pool is
// smaller than anyone remembers"* - a saturation question, and the instrument for that is a fixed
// number of workers each waiting for its answer before sending again, holding the system exactly
// full and never more. That is workload-ceiling's reasoning applied one stratum down, and ADR 0016's
// open model is the right instrument for the other question rather than for this one: an open model
// here would measure how fast a queue grew, which is a fact about the queue.
//
// # The two limits this tier has, and why they are separated
//
// Tomcat 8.5 accepts connections with a thread pool (`maxThreads`, 200 by default) and the WAR
// borrows connections from a DBCP pool the container binds (`maxTotal`, **8** by default). They are
// different numbers with different symptoms and a run that reports one figure for "the ceiling"
// cannot say which was reached. WP-23 separated the two lock timers for exactly this reason - one
// averaged wait that moves for two unrelated reasons answers neither question - so this reports
// **read** operations and **write** operations apart, and reports how latency grows against
// concurrency, which is the shape that tells the two pools apart:
//
//   - a request queued for a **connection** waits and then succeeds, so latency climbs with
//     concurrency while throughput stays flat and nothing fails;
//   - a request refused for want of a **thread** never reaches the WAR at all, so it fails at the
//     socket rather than arriving late.
//
// # Why the references come from a file
//
// The master holds what workload/scripts/legacy-up.sh seeded into it, read back out of the database
// rather than re-derived. A driver that invented references would spend the run collecting
// ACCT_NOT_FOUND faults and would measure the fault path - which is F-18 one stratum up, and the
// mistake this repository has already made once at volume.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/hardware"
	"github.com/k-napiontek/tessera-bank/workload/internal/soap"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "workload-soap: %v\n", err)
		os.Exit(1)
	}
}

// reference is one account and the customer that holds it, as legacy-up.sh wrote them out.
type reference struct {
	Account  string
	Customer string
}

func run() error {
	var (
		endpoint     string
		accountsPath string
		levels       string
		duration     time.Duration
		timeout      time.Duration
		reportPath   string
		writes       bool
	)
	flag.StringVar(&endpoint, "endpoint", "http://localhost:18080/customer-master/services/CustomerMasterService",
		"the CustomerMasterService endpoint")
	flag.StringVar(&accountsPath, "accounts", "", "file of `accountRef customerRef` lines (required)")
	flag.StringVar(&levels, "levels", "1,2,4,8,16,32,64", "concurrency levels to walk, in order")
	flag.DurationVar(&duration, "duration", 10*time.Second, "how long to hold each level")
	flag.DurationVar(&timeout, "timeout", 30*time.Second, "per-call timeout")
	flag.StringVar(&reportPath, "report", "", "write the report here as JSON as well as to stdout")
	flag.BoolVar(&writes, "writes", false,
		"also drive NotifyTransferPosted, which writes to the master through its PL/SQL package")
	flag.Parse()

	if accountsPath == "" {
		return fmt.Errorf("--accounts is required; workload/scripts/legacy-up.sh writes the file")
	}
	refs, err := readReferences(accountsPath)
	if err != nil {
		return err
	}
	if len(refs) < 2 {
		return fmt.Errorf("%s holds %d references; a run needs at least two", accountsPath, len(refs))
	}

	ladder, err := parseLevels(levels)
	if err != nil {
		return err
	}

	machine := hardware.Describe()
	fmt.Printf("== customer-master under load ==\n")
	fmt.Printf("  %s\n", machine)
	fmt.Printf("  endpoint %s\n", endpoint)
	fmt.Printf("  %d account references, %s per level\n\n", len(refs), duration)

	operations := []soap.Operation{soap.GetAccount, soap.GetAccountsByCustomer}
	if writes {
		operations = append(operations, soap.NotifyTransferPosted)
	}

	report := Report{
		Machine:    machine,
		Endpoint:   endpoint,
		References: len(refs),
		Duration:   duration.String(),
	}

	client := soap.New(endpoint, timeout)
	for _, operation := range operations {
		fmt.Printf("-- %s\n", operation)
		fmt.Printf("   %-8s %10s %10s %10s %10s %9s %9s\n",
			"workers", "rate/s", "mean", "p95", "max", "faulted", "unknown")
		for _, level := range ladder {
			measurement := hold(client, operation, refs, level, duration)
			measurement.Operation = operation.String()
			report.Levels = append(report.Levels, measurement)
			fmt.Printf("   %-8d %10.1f %10s %10s %10s %9d %9d\n",
				level, measurement.RatePerSecond,
				round(measurement.MeanMillis), round(measurement.P95Millis), round(measurement.MaxMillis),
				measurement.Faulted, measurement.Unknown)
		}
		fmt.Println()
	}

	summarise(&report)
	fmt.Print(report.Verdict)

	if reportPath != "" {
		encoded, err := json.MarshalIndent(report, "", "  ")
		if err != nil {
			return err
		}
		if err := os.WriteFile(reportPath, append(encoded, '\n'), 0o644); err != nil {
			return err
		}
		fmt.Printf("\nwritten to %s\n", reportPath)
	}
	return nil
}

// Level is one rung: what the endpoint did while exactly this many workers were kept busy.
type Level struct {
	Operation     string  `json:"operation"`
	Workers       int     `json:"workers"`
	Calls         int64   `json:"calls"`
	Answered      int64   `json:"answered"`
	Faulted       int64   `json:"faulted"`
	Rejected      int64   `json:"rejected"`
	Unknown       int64   `json:"unknown"`
	RatePerSecond float64 `json:"ratePerSecond"`
	MeanMillis    float64 `json:"meanMillis"`
	P95Millis     float64 `json:"p95Millis"`
	MaxMillis     float64 `json:"maxMillis"`
}

// Report is the whole ladder, in the form a capture can be committed as.
type Report struct {
	Machine    string  `json:"machine"`
	Endpoint   string  `json:"endpoint"`
	References int     `json:"references"`
	Duration   string  `json:"duration"`
	Levels     []Level `json:"levels"`
	Verdict    string  `json:"verdict"`
}

// hold keeps exactly `workers` calls in flight for `duration` and reports what came back.
func hold(client *soap.Client, operation soap.Operation, refs []reference, workers int, duration time.Duration) Level {
	ctx, cancel := context.WithTimeout(context.Background(), duration)
	defer cancel()

	var (
		mu        sync.Mutex
		latencies []time.Duration
		answered  atomic.Int64
		faulted   atomic.Int64
		rejected  atomic.Int64
		unknown   atomic.Int64
	)

	started := time.Now()
	var wait sync.WaitGroup
	for worker := 0; worker < workers; worker++ {
		wait.Add(1)
		go func(worker int) {
			defer wait.Done()
			// Each worker walks its own slice of the references, so what saturates is the endpoint
			// rather than one row in the master. workload-ceiling gives each worker its own pair of
			// accounts for the same reason, and names it.
			next := worker
			for call := 0; ctx.Err() == nil; call++ {
				ref := refs[next%len(refs)]
				counterparty := refs[(next+1)%len(refs)]
				next += workers

				result := client.Call(ctx, operation, envelopeFor(operation, ref, counterparty, worker, call))
				if ctx.Err() != nil && result.Outcome == soap.Unknown {
					// The level ended while this call was in flight. It is the run stopping rather
					// than the endpoint failing, and counting it would put a bar on every chart at
					// exactly the moment the clock ran out.
					return
				}

				switch result.Outcome {
				case soap.Answered:
					answered.Add(1)
				case soap.Faulted:
					faulted.Add(1)
				case soap.Rejected:
					rejected.Add(1)
				default:
					unknown.Add(1)
				}

				mu.Lock()
				latencies = append(latencies, result.Latency)
				mu.Unlock()
			}
		}(worker)
	}
	wait.Wait()
	elapsed := time.Since(started)

	level := Level{
		Workers:  workers,
		Answered: answered.Load(),
		Faulted:  faulted.Load(),
		Rejected: rejected.Load(),
		Unknown:  unknown.Load(),
	}
	level.Calls = level.Answered + level.Faulted + level.Rejected + level.Unknown
	if elapsed > 0 {
		level.RatePerSecond = float64(level.Calls) / elapsed.Seconds()
	}
	level.MeanMillis, level.P95Millis, level.MaxMillis = distribution(latencies)
	return level
}

// envelopeFor builds the request for one operation against one reference.
func envelopeFor(operation soap.Operation, ref, counterparty reference, worker, call int) []byte {
	switch operation {
	case soap.GetAccount:
		return soap.GetAccountRequest(ref.Account)
	case soap.GetAccountsByCustomer:
		return soap.GetAccountsByCustomerRequest(ref.Customer)
	default:
		return notification(ref, counterparty, worker, call)
	}
}

// notification builds a NotifyTransferPosted whose transfer reference is unique to this worker and
// call. The operation is idempotent by design - it answers alreadyApplied rather than faulting - so
// a repeated reference would measure the duplicate path rather than the posting path, which is the
// same mistake as driving a master that holds none of the references.
//
// The two legs name two different accounts, because the endpoint refuses SAME_ACCOUNT and it is
// right to: a transfer from an account to itself is not a transfer. The first version of this driver
// named one account twice and every single call faulted - a full ladder of measurements of the
// validation path, which is F-18's mistake in a third costume. The endpoint caught it.
func notification(ref, counterparty reference, worker, call int) []byte {
	// TB followed by exactly eighteen digits, which is what TransferRefType's pattern says and what
	// APPLIED_TRANSFER_REF_CK enforces a second time in the database. The driver's first version
	// invented its own WL-prefixed shape and every call faulted on the constraint - the contract and
	// the schema agreeing with each other, against the driver, which is what both are for.
	transferRef := fmt.Sprintf("TB%09d%09d", worker, call)
	transfer := soap.Transfer{
		TransferRef:      transferRef,
		DebitAccountRef:  ref.Account,
		CreditAccountRef: counterparty.Account,
		AmountMinor:      1_00,
		Currency:         "PLN",
		Status:           "POSTED",
		Reference:        "WORKLOAD SOAP LADDER",
		RequestedAt:      "2026-03-02T09:15:00Z",
		PostedAt:         "2026-03-02T09:15:00Z",
		CorrelationID:    fmt.Sprintf("%08x-0000-4000-8000-%012d", worker, call),
	}
	debit := soap.Movement{
		MovementRef: transferRef + "-01",
		TransferRef: transferRef,
		LegNo:       1,
		AccountRef:  ref.Account,
		Direction:   "D",
		AmountMinor: 1_00,
		Currency:    "PLN",
		ValueDate:   "2026-03-02",
		PostedAt:    "2026-03-02T09:15:00Z",
	}
	credit := debit
	credit.MovementRef = transferRef + "-02"
	credit.LegNo = 2
	credit.AccountRef = counterparty.Account
	credit.Direction = "C"
	return soap.NotifyTransferPostedRequest(transfer, debit, credit)
}

func distribution(latencies []time.Duration) (mean, p95, max float64) {
	if len(latencies) == 0 {
		return 0, 0, 0
	}
	sorted := make([]time.Duration, len(latencies))
	copy(sorted, latencies)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })

	var total time.Duration
	for _, one := range sorted {
		total += one
	}
	mean = float64(total.Microseconds()) / float64(len(sorted)) / 1000
	index := int(float64(len(sorted))*0.95) - 1
	if index < 0 {
		index = 0
	}
	p95 = float64(sorted[index].Microseconds()) / 1000
	max = float64(sorted[len(sorted)-1].Microseconds()) / 1000
	return mean, p95, max
}

// summarise states what the ladder shows, in the terms the two pools can be told apart in.
func summarise(report *Report) {
	var out strings.Builder
	out.WriteString("== What the ladder shows ==\n")

	byOperation := map[string][]Level{}
	var order []string
	for _, level := range report.Levels {
		if _, seen := byOperation[level.Operation]; !seen {
			order = append(order, level.Operation)
		}
		byOperation[level.Operation] = append(byOperation[level.Operation], level)
	}

	for _, operation := range order {
		levels := byOperation[operation]
		best, at := 0.0, 0
		for _, level := range levels {
			if level.RatePerSecond > best {
				best, at = level.RatePerSecond, level.Workers
			}
		}
		first, last := levels[0], levels[len(levels)-1]

		fmt.Fprintf(&out, "  %-22s peak %.1f/s at %d workers\n", operation, best, at)
		fmt.Fprintf(&out, "  %-22s mean latency %.1f ms at %d workers, %.1f ms at %d\n",
			"", first.MeanMillis, first.Workers, last.MeanMillis, last.Workers)

		var failed, faulted, calls int64
		for _, level := range levels {
			failed += level.Unknown + level.Rejected
			faulted += level.Faulted
			calls += level.Calls
		}
		if calls > 0 && faulted*2 > calls {
			// More than half the ladder was a refusal the endpoint was entitled to give, so the
			// figures above describe the validation path rather than the operation. Said first and
			// plainly, because a rate is the easiest number in this report to quote out of context.
			fmt.Fprintf(&out, "  %-22s %d of %d calls FAULTED - this ladder measured the refusal "+
				"path, not the operation. Read no throughput figure off it\n", "", faulted, calls)
		} else if failed == 0 {
			// Everything answered, late. That rules out Tomcat's connector - a thread pool that ran
			// out refuses at the socket rather than answering slowly - and it says the demand queued
			// behind something of fixed capacity. It does **not** say which something: this run
			// cannot tell a datasource pool from a saturated CPU, because both answer everything and
			// both make latency grow with concurrency. Naming the datasource here would be asserting
			// a mechanism from a shape, and moving `maxTotal` between two runs is what settles it.
			fmt.Fprintf(&out, "  %-22s not one call failed at any level, so this is not the "+
				"connector: the demand queued behind something of fixed capacity and was answered "+
				"late. Which resource that is takes a second run with one setting moved\n", "")
		} else {
			// A refusal at the socket is the connector's signature, and it is the one failure mode
			// that can be named from a single run.
			fmt.Fprintf(&out, "  %-22s %d calls were refused or never answered - the connector "+
				"rather than anything behind it, because a queue behind the WAR answers late\n", "", failed)
		}
	}
	report.Verdict = out.String()
}

func readReferences(path string) ([]reference, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer func() { _ = file.Close() }()

	var refs []reference
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		refs = append(refs, reference{Account: fields[0], Customer: fields[1]})
	}
	return refs, scanner.Err()
}

func parseLevels(levels string) ([]int, error) {
	var ladder []int
	for _, one := range strings.Split(levels, ",") {
		value, err := strconv.Atoi(strings.TrimSpace(one))
		if err != nil || value < 1 {
			return nil, fmt.Errorf("--levels carries %q, which is not a worker count", one)
		}
		ladder = append(ladder, value)
	}
	return ladder, nil
}

func round(millis float64) string {
	return strconv.FormatFloat(millis, 'f', 1, 64) + "ms"
}
