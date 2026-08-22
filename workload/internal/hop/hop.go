// Package hop turns what the four-era hop left behind into what it cost.
//
// # Why the adapter's own log is the instrument
//
// `integration/esb-adapter` exposes no metrics at all: no actuator, no Micrometer, and not even a
// web starter to put an endpoint on. That is F-100's situation one stratum up, and the answer is the
// same one WP-25b gave for `customer-master` - **observe it from outside, rather than modernise a
// Boot 2.7 component to make it measurable.** What it does have is two INFO lines per transfer that
// WP-11b already wrote for operators, and they happen to bracket the one step nobody has timed.
//
// # Three legs, and only one of them is measured
//
// This distinction is the whole reason this package exists rather than a shell script averaging
// timestamps:
//
//   - **file** is `crossed to stratum 0` minus `carried to the system of record`. It is *measured*:
//     both ends are logged, and between them the adapter does exactly one thing - append to the
//     movement file, which means scanning every record already in it under an exclusive lock and
//     forcing the result to disk.
//   - **inbound** is one transfer's `carried` minus the previous transfer's `crossed`. It is a
//     *difference*, because nothing is logged when a message is picked up. It contains the poll, the
//     XSLT transform, the schema validation, the JAXB unmarshal and the SOAP call - **not the SOAP
//     call alone**, and every figure derived from it says so.
//   - **service** is one `crossed` to the next. It is the hop's service time, and its reciprocal is
//     the hop's throughput.
//
// Reporting the second as "SOAP latency" would be the kind of plausible wrong number this repository
// keeps a trap list about, so the leg carries what it contains in a field of its own.
//
// # What it never does
//
// It performs no I/O. cmd/workload-hop hands it text and samples, exactly as cmd/workload-soak hands
// internal/soak its scrapes, and it is driver by role rather than by purity for the same reason.
package hop

import (
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
)

// The adapter's own wording. Matched as written rather than by a loose contains, because these are
// the only lines that mean a stage completed - and a substring that also matched the WARN line would
// count a retry as a crossing.
const (
	carried = " carried to the system of record"
	crossed = " crossed to stratum 0"
)

// timestamp is Spring Boot 2.7's default pattern. The module has no logback configuration, so this
// is what it writes; it was read off the running jar rather than assumed from the documentation.
const timestamp = "2006-01-02 15:04:05.000"

// Crossing is one transfer's passage through the adapter, as the adapter's own log records it.
type Crossing struct {
	Ref     string    `json:"ref"`
	Carried time.Time `json:"-"`
	Crossed time.Time `json:"-"`
	// AlreadyApplied is the system of record answering that it held the transfer already, which is
	// what makes at-least-once delivery safe. AlreadyInFile is the movement file's own constraint
	// saying the same thing - and the two can disagree, which is exactly why ADR 0014 asks the file.
	AlreadyApplied bool `json:"alreadyApplied,omitempty"`
	AlreadyInFile  bool `json:"alreadyInFile,omitempty"`
}

// FileLeg is the movement-file append, measured directly between two logged instants.
func (c Crossing) FileLeg() time.Duration { return c.Crossed.Sub(c.Carried) }

// Failures is what did not cross, by the route it took.
type Failures struct {
	// Transient is a message that was not acknowledged. The partition waits behind it, deliberately -
	// ordering is what that buys and blocking is what it costs.
	Transient int `json:"transient"`
	// DeadLettered is a permanent refusal recorded on the dead-letter channel and acknowledged.
	DeadLettered int `json:"deadLettered"`
	// Unexpected is an exception nobody classified, treated as transient because the alternative is
	// discarding a payment on the strength of a bug.
	Unexpected int `json:"unexpected"`
}

// Sample is one instant of the broker and the movement file together.
type Sample struct {
	ElapsedSeconds  float64 `json:"elapsedSeconds"`
	AdapterAssigned bool    `json:"adapterAssigned"`
	AdapterLag      int64   `json:"adapterLag"`
	AdapterLagKnown bool    `json:"adapterLagKnown"`
	ScorerAssigned  bool    `json:"scorerAssigned"`
	ScorerLag       int64   `json:"scorerLag"`
	ScorerLagKnown  bool    `json:"scorerLagKnown"`
	DeadLetters     int64   `json:"deadLetters"`
	MovementRecords int64   `json:"movementRecords"`
}

// Conditions is what the run was, so a figure can be read against the run that produced it.
// `workload/baselines/README.md`: a measurement names its conditions or it is worthless.
type Conditions struct {
	Machine         string `json:"machine"`
	Endpoint        string `json:"endpoint"`
	BusinessDate    string `json:"businessDate,omitempty"`
	Customers       int    `json:"customers,omitempty"`
	Accounts        int    `json:"accounts,omitempty"`
	Scale           string `json:"scale,omitempty"`
	Compression     int    `json:"compression,omitempty"`
	Seed            int64  `json:"seed,omitempty"`
	Partitions      int    `json:"partitions,omitempty"`
	Concurrency     int    `json:"listenerConcurrency,omitempty"`
	RelayBatch      int    `json:"relayBatch,omitempty"`
	RelayIntervalMs int    `json:"relayIntervalMs,omitempty"`
	IntervalSeconds int    `json:"sampleIntervalSeconds,omitempty"`
	BoundSeconds    int    `json:"boundSeconds,omitempty"`
	// Buckets is how many equal slices the day is cut into to show how cost moved across it.
	Buckets int `json:"driftBuckets,omitempty"`
}

// Leg is one stage of the hop, with what it contains stated beside what it cost.
type Leg struct {
	Name       string  `json:"name"`
	What       string  `json:"what"`
	Count      int     `json:"count"`
	MeanMillis float64 `json:"meanMillis"`
	P95Millis  float64 `json:"p95Millis"`
	MaxMillis  float64 `json:"maxMillis"`
}

// Bucket is one slice of the day in the order transfers crossed, so a cost that moves with the size
// of the movement file is visible rather than averaged away.
type Bucket struct {
	From              int     `json:"fromTransfer"`
	To                int     `json:"toTransfer"`
	FileMeanMillis    float64 `json:"fileMeanMillis"`
	ServiceMeanMillis float64 `json:"serviceMeanMillis"`
	PerSecond         float64 `json:"transfersPerSecond"`
}

// Report is the whole hop, in the form a capture can be committed as.
type Report struct {
	Conditions  Conditions `json:"conditions"`
	Crossings   int        `json:"crossings"`
	Redelivered int        `json:"redelivered"`
	Failures    Failures   `json:"failures"`
	Legs        []Leg      `json:"legs"`
	Drift       []Bucket   `json:"drift"`
	// FileLegGrowth is the last bucket's mean file leg over the first's. Above 1 means appending got
	// dearer as the file grew, which is what a linear scan per message produces.
	FileLegGrowth float64  `json:"fileLegGrowth"`
	PeakLag       int64    `json:"peakLag"`
	PeakLagSeen   bool     `json:"peakLagSeen"`
	ClosingLag    int64    `json:"closingLag"`
	Drained       bool     `json:"drained"`
	EverAssigned  bool     `json:"everAssigned"`
	DeadLetters   int64    `json:"deadLetters"`
	Elapsed       float64  `json:"elapsedSeconds"`
	Samples       []Sample `json:"samples"`
	Verdict       string   `json:"verdict"`
}

// ParseLog reads the adapter's own log into crossings and failures.
//
// A line it does not recognise is skipped rather than refused: the log is mostly Kafka's and the
// JVM's, and a parser that fell over on those would never read a real one.
func ParseLog(text string) ([]Crossing, Failures, error) {
	var (
		crossings []Crossing
		failures  Failures
		open      = map[string]Crossing{}
	)

	for _, line := range strings.Split(text, "\n") {
		switch {
		case strings.Contains(line, carried):
			ref, at, ok := refAndTime(line, carried)
			if !ok {
				continue
			}
			open[ref] = Crossing{
				Ref:            ref,
				Carried:        at,
				AlreadyApplied: strings.Contains(line, "(already applied)"),
			}

		case strings.Contains(line, crossed):
			ref, at, ok := refAndTime(line, crossed)
			if !ok {
				continue
			}
			// A crossing with no carrying before it cannot be timed. It happens when the log was
			// rotated or the sampler started mid-transfer, and dropping it is the only honest
			// answer - the alternative is a leg measured from nothing.
			started, seen := open[ref]
			if !seen {
				continue
			}
			delete(open, ref)
			started.Crossed = at
			started.AlreadyInFile = strings.Contains(line, "(already in the movement file)")
			crossings = append(crossings, started)

		case strings.Contains(line, "will be retried:"):
			failures.Transient++
		case strings.Contains(line, "failed unexpectedly"):
			failures.Unexpected++
		case strings.Contains(line, "dead-lettering"):
			failures.DeadLettered++
		}
	}

	return crossings, failures, nil
}

// refAndTime pulls the transfer reference and the instant off one line. The reference is the token
// after the word "transfer", which is how every one of these lines is worded.
func refAndTime(line, marker string) (string, time.Time, bool) {
	at, err := time.ParseInLocation(timestamp, firstN(line, len(timestamp)), time.Local)
	if err != nil {
		return "", time.Time{}, false
	}

	before := line[:strings.Index(line, marker)]
	fields := strings.Fields(before)
	if len(fields) == 0 {
		return "", time.Time{}, false
	}
	ref := fields[len(fields)-1]
	if !strings.HasPrefix(ref, "TB") {
		return "", time.Time{}, false
	}
	return ref, at, true
}

func firstN(text string, n int) string {
	if len(text) < n {
		return text
	}
	return text[:n]
}

// Summarise turns crossings and samples into the report a capture is committed as.
func Summarise(crossings []Crossing, failures Failures, samples []Sample, conditions Conditions) Report {
	if conditions.Buckets <= 0 {
		conditions.Buckets = 10
	}

	sorted := make([]Crossing, len(crossings))
	copy(sorted, crossings)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Crossed.Before(sorted[j].Crossed) })

	report := Report{
		Conditions: conditions,
		Crossings:  len(sorted),
		Failures:   failures,
		Samples:    samples,
	}
	for _, one := range sorted {
		if one.AlreadyInFile {
			report.Redelivered++
		}
	}

	report.Legs = legsOf(sorted)
	report.Drift, report.FileLegGrowth = driftOf(sorted, conditions.Buckets)
	readSamples(&report, samples)
	report.Verdict = verdictOf(report)
	return report
}

func legsOf(sorted []Crossing) []Leg {
	var file, inbound, service []time.Duration
	for at, one := range sorted {
		file = append(file, one.FileLeg())
		if at > 0 {
			inbound = append(inbound, one.Carried.Sub(sorted[at-1].Crossed))
			service = append(service, one.Crossed.Sub(sorted[at-1].Crossed))
		}
	}

	return []Leg{
		leg("file", "measured directly between two logged instants: the movement-file append, "+
			"its de-duplication scan over every record already there, and the fsync", file),
		leg("inbound", "a difference, not a measurement - nothing is logged when a message is "+
			"picked up. It holds the poll, the XSLT transform, the schema validation, the JAXB "+
			"unmarshal and the SOAP call, so it is not the SOAP call alone", inbound),
		leg("service", "one crossing to the next: what the hop takes per transfer end to end, "+
			"whose reciprocal is its throughput", service),
	}
}

func leg(name, what string, durations []time.Duration) Leg {
	mean, p95, max := distribution(durations)
	return Leg{Name: name, What: what, Count: len(durations),
		MeanMillis: mean, P95Millis: p95, MaxMillis: max}
}

// driftOf cuts the day into equal slices in the order transfers crossed. A mean over the whole day
// would hide the one thing worth finding here: whether the cost per transfer moves as the movement
// file grows underneath it.
func driftOf(sorted []Crossing, buckets int) ([]Bucket, float64) {
	if len(sorted) < buckets || buckets < 2 {
		return nil, 0
	}

	out := make([]Bucket, 0, buckets)
	size := len(sorted) / buckets

	for index := 0; index < buckets; index++ {
		from := index * size
		to := from + size
		if index == buckets-1 {
			to = len(sorted)
		}

		slice := sorted[from:to]
		var file, service []time.Duration
		for at, one := range slice {
			file = append(file, one.FileLeg())
			previous := from + at - 1
			if previous >= 0 {
				service = append(service, one.Crossed.Sub(sorted[previous].Crossed))
			}
		}

		fileMean, _, _ := distribution(file)
		serviceMean, _, _ := distribution(service)
		bucket := Bucket{From: from + 1, To: to, FileMeanMillis: fileMean, ServiceMeanMillis: serviceMean}
		if serviceMean > 0 {
			bucket.PerSecond = round(1000 / serviceMean)
		}
		out = append(out, bucket)
	}

	growth := 0.0
	if first := out[0].FileMeanMillis; first > 0 {
		growth = round(out[len(out)-1].FileMeanMillis / first)
	}
	return out, growth
}

func readSamples(report *Report, samples []Sample) {
	for _, sample := range samples {
		if sample.AdapterAssigned {
			report.EverAssigned = true
		}
		if sample.AdapterLagKnown && (!report.PeakLagSeen || sample.AdapterLag > report.PeakLag) {
			report.PeakLag = sample.AdapterLag
			report.PeakLagSeen = true
		}
		report.DeadLetters = sample.DeadLetters
		report.Elapsed = sample.ElapsedSeconds
	}
	if len(samples) == 0 {
		return
	}

	last := samples[len(samples)-1]
	report.ClosingLag = last.AdapterLag
	// Drained means the backlog reached zero *and* something was holding the group while it did.
	// A group nothing ever consumed also ends at a small number, and calling that drained would be
	// reporting a stalled hop as a fast one.
	report.Drained = report.EverAssigned && last.AdapterLagKnown && last.AdapterLag == 0
}

// verdictOf states every outcome that is true rather than the first one that matched. A run in
// which nothing crossed *and* a backlog stood is two findings, and a verdict that reported only one
// of them would leave the other to be inferred from a table.
func verdictOf(report Report) string {
	var out strings.Builder
	out.WriteString("== What the hop shows ==\n\n")

	// The one branch that does short-circuit, because it invalidates every other figure: a lag in
	// front of a group nothing holds is not this estate's throughput being measured.
	if len(report.Samples) > 0 && !report.EverAssigned {
		out.WriteString("  The consumer group was never held by anything. Every lag figure below is\n")
		out.WriteString("  the backlog standing in front of an adapter that was not consuming, and\n")
		out.WriteString("  none of them is a measurement of this estate's throughput.\n")
		return out.String()
	}

	if len(report.Samples) > 0 {
		if report.Drained {
			out.WriteString(fmt.Sprintf(
				"  A backlog of %d formed and drained. The hop kept up in the end; what it cost to\n"+
					"  do so is the service leg below.\n", report.PeakLag))
		} else {
			out.WriteString(fmt.Sprintf(
				"  The backlog did not drain. %d events were still unread at the last sample, %.0f s\n"+
					"  in. That is the measurement rather than a failure of the run: the tier below is\n"+
					"  slower than the tier above, and this is by how much.\n",
				report.ClosingLag, report.Elapsed))
		}
	}

	if report.Crossings == 0 {
		out.WriteString("\n  Nothing crossed to stratum 0. Read no throughput figure off this run.\n")
	}

	if report.FileLegGrowth > 1.05 {
		out.WriteString(fmt.Sprintf(
			"\n  Appending to the movement file got %.1fx dearer between the first slice of the day\n"+
				"  and the last. The writer scans every record already in the file before it appends,\n"+
				"  so the cost of writing transfer n is proportional to n.\n", report.FileLegGrowth))
	}

	if report.Failures.Transient > 0 {
		out.WriteString(fmt.Sprintf(
			"\n  %d messages were not acknowledged and were redelivered. Everything behind them on\n"+
				"  the partition waited, which is what ordering costs.\n", report.Failures.Transient))
	}
	if report.Failures.DeadLettered > 0 {
		out.WriteString(fmt.Sprintf(
			"\n  %d transfers reached the dead-letter path.\n", report.Failures.DeadLettered))
	}

	return out.String()
}

// Render is the report as a page, in the same shape every other capture in this module prints.
func (r Report) Render() string {
	var out strings.Builder

	out.WriteString("== Conditions ==\n\n")
	out.WriteString("  machine       " + r.Conditions.Machine + "\n")
	out.WriteString("  endpoint      " + r.Conditions.Endpoint + "\n")
	if r.Conditions.BusinessDate != "" {
		out.WriteString("  business date " + r.Conditions.BusinessDate + "\n")
	}
	if r.Conditions.Accounts > 0 {
		out.WriteString(fmt.Sprintf("  population    %d customers, %d accounts\n",
			r.Conditions.Customers, r.Conditions.Accounts))
	}
	if r.Conditions.Compression > 0 {
		out.WriteString(fmt.Sprintf("  dials         scale %s, %dx, seed %d\n",
			r.Conditions.Scale, r.Conditions.Compression, r.Conditions.Seed))
	}
	if r.Conditions.Partitions > 0 {
		out.WriteString(fmt.Sprintf("  transport     %d partition(s), listener concurrency %d, "+
			"relay %d rows / %d ms\n",
			r.Conditions.Partitions, r.Conditions.Concurrency,
			r.Conditions.RelayBatch, r.Conditions.RelayIntervalMs))
	}

	out.WriteString("\n== The hop ==\n\n")
	out.WriteString(fmt.Sprintf("  crossed to stratum 0    %d\n", r.Crossings))
	out.WriteString(fmt.Sprintf("  already in the file     %d\n", r.Redelivered))
	out.WriteString(fmt.Sprintf("  redelivered (transient) %d\n", r.Failures.Transient))
	out.WriteString(fmt.Sprintf("  dead-lettered           %d\n", r.Failures.DeadLettered))
	out.WriteString(fmt.Sprintf("  peak consumer lag       %d\n", r.PeakLag))
	out.WriteString(fmt.Sprintf("  closing consumer lag    %d\n", r.ClosingLag))

	out.WriteString("\n== The three legs ==\n\n")
	out.WriteString(fmt.Sprintf("  %-9s %8s %10s %10s %10s\n", "leg", "count", "mean ms", "p95 ms", "max ms"))
	for _, leg := range r.Legs {
		out.WriteString(fmt.Sprintf("  %-9s %8d %10.1f %10.1f %10.1f\n",
			leg.Name, leg.Count, leg.MeanMillis, leg.P95Millis, leg.MaxMillis))
	}
	out.WriteString("\n")
	for _, leg := range r.Legs {
		out.WriteString("  " + leg.Name + ": " + leg.What + "\n")
	}

	if len(r.Drift) > 0 {
		out.WriteString("\n== What it cost across the day ==\n\n")
		out.WriteString(fmt.Sprintf("  %-16s %12s %14s %12s\n",
			"transfers", "file ms", "service ms", "per second"))
		for _, bucket := range r.Drift {
			out.WriteString(fmt.Sprintf("  %-16s %12.1f %14.1f %12.1f\n",
				fmt.Sprintf("%d-%d", bucket.From, bucket.To),
				bucket.FileMeanMillis, bucket.ServiceMeanMillis, bucket.PerSecond))
		}
	}

	out.WriteString("\n" + r.Verdict)
	return out.String()
}

func distribution(durations []time.Duration) (mean, p95, max float64) {
	if len(durations) == 0 {
		return 0, 0, 0
	}
	sorted := make([]time.Duration, len(durations))
	copy(sorted, durations)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })

	var total time.Duration
	for _, one := range sorted {
		total += one
	}
	mean = round(float64(total.Microseconds()) / float64(len(sorted)) / 1000)
	index := int(float64(len(sorted))*0.95) - 1
	if index < 0 {
		index = 0
	}
	return mean, round(float64(sorted[index].Microseconds()) / 1000), round(float64(sorted[len(sorted)-1].Microseconds()) / 1000)
}

// round to one decimal, so a committed capture does not carry seventeen digits of float noise that
// differ between machines and make two captures impossible to diff.
func round(value float64) float64 { return math.Round(value*10) / 10 }
