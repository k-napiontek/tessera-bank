// Command workload-hop watches the four-era hop while a bank day crosses it.
//
//	go -C workload run ./cmd/workload-hop \
//	  --broker-container tessera-workload-kafka \
//	  --movement-file /tmp/tessera-four-era/MOVEMENT.DAT \
//	  --adapter-log /tmp/tessera-four-era/adapter.log \
//	  --report baselines/four-era/hop.json
//
// # Why it watches from outside
//
// `integration/esb-adapter` publishes nothing about itself: no actuator, no Micrometer, no web
// starter. Adding one would be modernising a Boot 2.7 component to make it measurable, which is what
// WP-24's Constraint refused and F-85 recorded rather than worked around - so everything here is
// observed the way WP-25b observed stratum 1. Three instruments, none of them inside the component:
//
//   - **the broker's own consumer-group listing**, for how far behind the adapter is;
//   - **the movement file's length**, which at 120 bytes a record is the hop's completed-work
//     counter and needs nothing instrumented at all;
//   - **the adapter's own INFO log**, which WP-11b wrote for operators and which happens to bracket
//     the one step nobody has timed.
//
// # Why the scorer is sampled too
//
// `fraud-scoring` consumes the same topic in its own group, and it is the control. If the adapter
// falls behind and the scorer does not, the broker is not what is slow - which is the question a
// single lag figure cannot answer, and the same shape as WP-25b's second ladder run.
//
// # What it will not do
//
// It never resets an offset and never produces to a topic. It stops sampling when the backlog has
// drained or when its bound expires, and **a bound that expires is a measurement rather than a
// failure**: the tier below being slower than the tier above is the thing this exercise exists to
// find, and a driver that exited non-zero on it would be calling the finding an error.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/consumer"
	"github.com/k-napiontek/tessera-bank/workload/internal/hardware"
	"github.com/k-napiontek/tessera-bank/workload/internal/hop"
)

// One MOVEREC, per contracts/copybook/MOVEREC.CPY. The file is fixed-width and its length divided by
// this is exactly how many movement records the hop has written - two per transfer.
const movementRecordLength = 120

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "workload-hop: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	var (
		brokerContainer = flag.String("broker-container", "tessera-workload-kafka", "the broker container the fixture booted")
		bootstrap       = flag.String("bootstrap", "localhost:9092", "the broker address from inside that container, which is not the one the host publishes")
		group           = flag.String("group", "esb-adapter", "the adapter's consumer group")
		scorerGroup     = flag.String("scorer-group", "fraud-scoring", "the scorer's consumer group, which is the control")
		deadLetterTopic = flag.String("dead-letter-topic", "tessera.esb.transfer-posted.dlt.v1", "the adapter's dead-letter topic")
		movementFile    = flag.String("movement-file", "", "the movement file the adapter appends to (required)")
		adapterLog      = flag.String("adapter-log", "", "the adapter's own log, which is where the legs are timed from")
		interval        = flag.Duration("interval", 2*time.Second, "how often to sample")
		bound           = flag.Duration("bound", 15*time.Minute, "how long to keep sampling before reporting what stood")
		settled         = flag.Int("settled", 3, "consecutive zero-lag samples before the backlog counts as drained")
		buckets         = flag.Int("buckets", 10, "how many slices the day is cut into to show how cost moved across it")
		reportPath      = flag.String("report", "", "where to write the capture as JSON")
		outPath         = flag.String("out", "", "where to write the rendered report")

		// Conditions. A measurement names them or it is worthless - baselines/README.md.
		endpoint      = flag.String("endpoint", "", "the SOAP endpoint the adapter was pointed at")
		businessDate  = flag.String("business-date", "", "the date the day being watched belongs to")
		customers     = flag.Int("customers", 0, "the population the day was drawn from")
		accounts      = flag.Int("accounts", 0, "how many accounts that population opens")
		scale         = flag.String("scale", "", "the volume dial the day was drawn at")
		compress      = flag.Int("compress", 0, "the compression dial the day was driven at")
		seed          = flag.Int64("seed", 0, "the run seed")
		partitions    = flag.Int("partitions", 0, "how many partitions the topic has")
		concurrency   = flag.Int("listener-concurrency", 0, "how many consumer threads the listener declares")
		relayBatch    = flag.Int("relay-batch", 0, "the ledger's outbox batch size")
		relayInterval = flag.Int("relay-interval-ms", 0, "the ledger's outbox relay interval")
	)
	flag.Parse()

	if *movementFile == "" {
		return fmt.Errorf("--movement-file is required - it is the hop's completed-work counter, " +
			"and workload/scripts/four-era-day.sh passes the path it started the adapter with")
	}

	broker := consumer.Container{Name: *brokerContainer, Bootstrap: *bootstrap}
	conditions := hop.Conditions{
		Machine: hardware.Describe(), Endpoint: *endpoint, BusinessDate: *businessDate,
		Customers: *customers, Accounts: *accounts, Scale: *scale, Compression: *compress,
		Seed: *seed, Partitions: *partitions, Concurrency: *concurrency,
		RelayBatch: *relayBatch, RelayIntervalMs: *relayInterval,
		IntervalSeconds: int(interval.Seconds()), BoundSeconds: int(bound.Seconds()),
		Buckets: *buckets,
	}

	// Ctrl-C and the TERM the composing script sends both stop sampling and still report. A watcher
	// that produced nothing when it was asked to stop would throw away the whole run's evidence.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	fmt.Printf("== Watching the four-era hop ==\n\n")
	fmt.Printf("  machine        %s\n", conditions.Machine)
	fmt.Printf("  broker         %s (%s)\n", *brokerContainer, *bootstrap)
	fmt.Printf("  groups         %s, and %s as the control\n", *group, *scorerGroup)
	fmt.Printf("  movement file  %s\n", *movementFile)
	fmt.Printf("  sampling       every %s, bounded at %s\n\n", *interval, *bound)

	samples := sample(ctx, broker, sampling{
		group: *group, scorerGroup: *scorerGroup, deadLetterTopic: *deadLetterTopic,
		movementFile: *movementFile, interval: *interval, bound: *bound, settled: *settled,
	})

	crossings, failures, err := readLog(*adapterLog)
	if err != nil {
		return err
	}

	report := hop.Summarise(crossings, failures, samples, conditions)
	rendered := report.Render()
	fmt.Print("\n" + rendered)

	if *outPath != "" {
		if err := os.WriteFile(*outPath, []byte(rendered), 0o644); err != nil {
			return err
		}
		fmt.Printf("\nwritten to %s\n", *outPath)
	}
	if *reportPath != "" {
		encoded, err := json.MarshalIndent(report, "", "  ")
		if err != nil {
			return err
		}
		if err := os.WriteFile(*reportPath, append(encoded, '\n'), 0o644); err != nil {
			return err
		}
		fmt.Printf("written to %s\n", *reportPath)
	}
	return nil
}

type sampling struct {
	group, scorerGroup, deadLetterTopic, movementFile string
	interval, bound                                   time.Duration
	settled                                           int
}

// sample walks the broker and the movement file until the backlog drains or the bound expires.
func sample(ctx context.Context, broker consumer.Broker, with sampling) []hop.Sample {
	var (
		samples   []hop.Sample
		started   = time.Now()
		deadline  = started.Add(with.bound)
		atZero    int
		published bool
		ticker    = time.NewTicker(with.interval)
	)
	defer ticker.Stop()

	fmt.Printf("  %8s %10s %10s %10s %12s %12s\n",
		"elapsed", "lag", "held", "scorer", "movements", "dead")

	for {
		one, logEnd, err := take(ctx, broker, with, started)
		if err != nil {
			// A broker that cannot be reached mid-run is worth saying out loud and worth carrying
			// on from: the next sample may well answer, and abandoning the run would throw away
			// every sample already taken.
			fmt.Fprintf(os.Stderr, "  (sample skipped: %v)\n", err)
		} else {
			samples = append(samples, one)
			if logEnd > 0 {
				published = true
			}
			fmt.Printf("  %8.0f %10s %10v %10s %12d %12d\n",
				one.ElapsedSeconds, lagText(one.AdapterLag, one.AdapterLagKnown),
				one.AdapterAssigned, lagText(one.ScorerLag, one.ScorerLagKnown),
				one.MovementRecords, one.DeadLetters)

			// Drained means the adapter held the group, its lag is zero, and something was actually
			// published for it to have consumed. Without that last condition a run that samples
			// before the relay's first batch reports a hop that kept up with nothing.
			if published && one.AdapterAssigned && one.AdapterLagKnown && one.AdapterLag == 0 {
				atZero++
			} else {
				atZero = 0
			}
			if atZero >= with.settled {
				fmt.Printf("\n  the backlog reached zero and stayed there for %d samples\n", atZero)
				return samples
			}
		}

		if time.Now().After(deadline) {
			fmt.Printf("\n  the %s bound expired; what stood at that moment is the measurement\n", with.bound)
			return samples
		}

		select {
		case <-ctx.Done():
			fmt.Printf("\n  stopped; reporting the %d samples taken so far\n", len(samples))
			return samples
		case <-ticker.C:
		}
	}
}

// take is one instant of the broker and the file together, so a sample is one moment rather than
// several taken as the run moved underneath it.
func take(ctx context.Context, broker consumer.Broker, with sampling, started time.Time) (hop.Sample, int64, error) {
	reading, err := consumer.Read(ctx, broker,
		[]string{with.group, with.scorerGroup}, with.deadLetterTopic)
	if err != nil {
		return hop.Sample{}, 0, err
	}

	one := hop.Sample{
		ElapsedSeconds: time.Since(started).Seconds(),
		DeadLetters:    reading.DeadLetters,
	}

	var logEnd int64
	if adapter, held := reading.Group(with.group); held {
		one.AdapterAssigned = adapter.Assigned()
		one.AdapterLag, one.AdapterLagKnown = adapter.TotalLag()
		for _, assignment := range adapter.Assignments {
			logEnd += assignment.LogEnd
		}
	}
	if scorer, held := reading.Group(with.scorerGroup); held {
		one.ScorerAssigned = scorer.Assigned()
		one.ScorerLag, one.ScorerLagKnown = scorer.TotalLag()
	}

	// A file that is not there yet is a hop that has written nothing, which is zero rather than an
	// error - the adapter creates it on its first append.
	if info, err := os.Stat(with.movementFile); err == nil {
		one.MovementRecords = info.Size() / movementRecordLength
	}

	return one, logEnd, nil
}

func readLog(path string) ([]hop.Crossing, hop.Failures, error) {
	if path == "" {
		return nil, hop.Failures{}, nil
	}
	text, err := os.ReadFile(path)
	if err != nil {
		return nil, hop.Failures{}, fmt.Errorf("the adapter's log at %s could not be read: %w", path, err)
	}
	return hop.ParseLog(string(text))
}

// lagText prints a dash for an unknown lag rather than a zero, which is the same refusal
// internal/consumer makes and for the same reason.
func lagText(lag int64, known bool) string {
	if !known {
		return "-"
	}
	return fmt.Sprintf("%d", lag)
}
