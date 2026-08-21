// Package metrics exposes what the run itself did, in Prometheus text format, on its own port.
//
// The driver's numbers are published beside the bank's rather than instead of them. A run is only
// interpretable when both are visible: `tessera_gateway_request_duration_seconds` says how long the
// edge took, and `tessera_workload_request_duration_seconds` says how long it took **from the time
// the schedule said the request should have gone out**. When those two diverge, the difference is
// the driver's own queueing, and a run that published only the first would be quietly reporting the
// estate's latency as though nobody had been late.
//
// Hand-written, because this module carries no dependencies at all: the exposition format is a
// dozen lines of text and the alternative is putting the Prometheus client library into a fixture.
// The names follow edge/api-gateway's - tessera_<component>_<thing>_<unit> - so that a dashboard
// built for one reads the other without translation.
package metrics

import (
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/runner"
)

// Buckets are the latency boundaries, in seconds. They start where the gateway's do and reach
// further: a request measured from its intended send time carries the driver's own lag as well as
// the estate's, and a run that falls behind produces figures a five-second tail would swallow.
var Buckets = []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60}

// Registry holds every instrument. Built per run rather than as package state: two runs in one
// process would otherwise add up into a number describing neither.
type Registry struct {
	mu sync.Mutex

	requests    map[pair]int64
	unsent      map[pair]int64
	latency     map[string]*histogram
	substituted int64
	retries     int64

	inFlight     int64
	peakInFlight int64
	lastLag      time.Duration
	maxLag       time.Duration
}

// pair is a two-label series. Both halves are bounded: an operation is one of eleven the contract
// declares, and an outcome is one of five. Never a path - it carries an account reference, and one
// series per account is the standard way to take a monitoring system down.
type pair struct{ first, second string }

// New builds an empty registry.
func New() *Registry {
	return &Registry{
		requests: map[pair]int64{},
		unsent:   map[pair]int64{},
		latency:  map[string]*histogram{},
	}
}

var _ runner.Observer = (*Registry)(nil)

// Result records one completed request.
func (r *Registry) Result(request client.Request, result client.Result) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.requests[pair{request.Operation, result.Outcome.String()}]++
	if request.CurrencySubstituted {
		r.substituted++
	}
	if result.Attempts > 1 {
		r.retries += int64(result.Attempts - 1)
	}

	observed := r.latency[request.Operation]
	if observed == nil {
		observed = newHistogram()
		r.latency[request.Operation] = observed
	}
	observed.observe(result.Latency)
}

// Unsent records a scheduled event that produced no request.
func (r *Registry) Unsent(operation, reason string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.unsent[pair{operation, reason}]++
}

// Lag records how far behind its own schedule the scheduler was.
func (r *Registry) Lag(behind time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastLag = behind
	if behind > r.maxLag {
		r.maxLag = behind
	}
}

// InFlight records the number of requests outstanding.
func (r *Registry) InFlight(current int) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.inFlight = int64(current)
	if r.inFlight > r.peakInFlight {
		r.peakInFlight = r.inFlight
	}
}

// Quantile is the latency at q across every operation, read off the buckets.
//
// Bucketed rather than exact, which is what Prometheus itself reports and what WP-23 will read: a
// figure taken from a different source than the dashboards would eventually disagree with them, and
// the disagreement would be discovered during an incident.
func (r *Registry) Quantile(q float64) time.Duration {
	r.mu.Lock()
	defer r.mu.Unlock()

	total := newHistogram()
	for _, observed := range r.latency {
		total.merge(observed)
	}
	return total.quantile(q)
}

// Write renders the exposition.
func (r *Registry) Write(w io.Writer) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	var out strings.Builder

	counter(&out, "tessera_workload_requests_total",
		"Requests the driver completed, by operation and outcome.", r.requests, "operation", "outcome")
	counter(&out, "tessera_workload_unsent_total",
		"Scheduled events that produced no request, by operation and reason.", r.unsent, "operation", "reason")

	single(&out, "tessera_workload_currency_substituted_total", "counter",
		"Transfers sent in the currency the accounts hold rather than the one the model drew.",
		float64(r.substituted))
	single(&out, "tessera_workload_retries_total", "counter",
		"Retries of an unknown outcome. Every one carried the key its first attempt used.",
		float64(r.retries))
	single(&out, "tessera_workload_in_flight", "gauge",
		"Requests outstanding now. Unbounded by design - the model is open.", float64(r.inFlight))
	single(&out, "tessera_workload_in_flight_peak", "gauge",
		"The most requests outstanding at once during this run.", float64(r.peakInFlight))
	single(&out, "tessera_workload_schedule_lag_seconds", "gauge",
		"How late the scheduler was when it last released an event. Rising means the driver, not the bank.",
		r.lastLag.Seconds())
	single(&out, "tessera_workload_schedule_lag_seconds_max", "gauge",
		"The worst the scheduler fell behind during this run.", r.maxLag.Seconds())

	out.WriteString("# HELP tessera_workload_request_duration_seconds Time from the request's " +
		"intended send time to its answer, retries included.\n")
	out.WriteString("# TYPE tessera_workload_request_duration_seconds histogram\n")
	for _, operation := range sorted(r.latency) {
		r.latency[operation].write(&out, "tessera_workload_request_duration_seconds",
			`operation="`+escape(operation)+`"`)
	}

	_, err := io.WriteString(w, out.String())
	return err
}

// Handler serves the exposition.
func (r *Registry) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		if err := r.Write(w); err != nil {
			// Nothing useful to say to a scraper whose connection has already gone.
			return
		}
	})
}

func counter(out *strings.Builder, name, help string, series map[pair]int64, first, second string) {
	fmt.Fprintf(out, "# HELP %s %s\n# TYPE %s counter\n", name, help, name)
	keys := make([]pair, 0, len(series))
	for key := range series {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i].first != keys[j].first {
			return keys[i].first < keys[j].first
		}
		return keys[i].second < keys[j].second
	})
	for _, key := range keys {
		fmt.Fprintf(out, "%s{%s=\"%s\",%s=\"%s\"} %d\n",
			name, first, escape(key.first), second, escape(key.second), series[key])
	}
}

func single(out *strings.Builder, name, kind, help string, value float64) {
	fmt.Fprintf(out, "# HELP %s %s\n# TYPE %s %s\n%s %s\n", name, help, name, kind, name, number(value))
}

// histogram is a cumulative-bucket latency histogram over Buckets.
type histogram struct {
	counts []int64
	sum    float64
	total  int64
}

func newHistogram() *histogram { return &histogram{counts: make([]int64, len(Buckets)+1)} }

func (h *histogram) observe(latency time.Duration) {
	seconds := latency.Seconds()
	h.sum += seconds
	h.total++
	for index, boundary := range Buckets {
		if seconds <= boundary {
			h.counts[index]++
			return
		}
	}
	h.counts[len(Buckets)]++
}

func (h *histogram) merge(other *histogram) {
	for index, count := range other.counts {
		h.counts[index] += count
	}
	h.sum += other.sum
	h.total += other.total
}

// quantile reads the value off the buckets, reporting the upper boundary of the bucket the
// quantile falls in. The last bucket has no upper boundary, so a quantile in it is reported as the
// largest boundary there is - stated rather than interpolated, because interpolating inside a
// bucket invents precision the histogram does not have.
func (h *histogram) quantile(q float64) time.Duration {
	if h.total == 0 {
		return 0
	}
	target := float64(h.total) * q
	var running int64
	for index, count := range h.counts {
		running += count
		if float64(running) >= target {
			if index >= len(Buckets) {
				return time.Duration(Buckets[len(Buckets)-1] * float64(time.Second))
			}
			return time.Duration(Buckets[index] * float64(time.Second))
		}
	}
	return time.Duration(Buckets[len(Buckets)-1] * float64(time.Second))
}

func (h *histogram) write(out *strings.Builder, name, labels string) {
	var cumulative int64
	for index, boundary := range Buckets {
		cumulative += h.counts[index]
		fmt.Fprintf(out, "%s_bucket{%s,le=\"%s\"} %d\n", name, labels, number(boundary), cumulative)
	}
	cumulative += h.counts[len(Buckets)]
	fmt.Fprintf(out, "%s_bucket{%s,le=\"+Inf\"} %d\n", name, labels, cumulative)
	fmt.Fprintf(out, "%s_sum{%s} %s\n", name, labels, number(h.sum))
	fmt.Fprintf(out, "%s_count{%s} %d\n", name, labels, h.total)
}

func sorted(series map[string]*histogram) []string {
	keys := make([]string, 0, len(series))
	for key := range series {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

// number renders a float the way the exposition format wants it: no exponent for ordinary values,
// and no trailing zeros to make a diff of two scrapes noisier than the change between them.
func number(value float64) string {
	return strconv.FormatFloat(value, 'g', -1, 64)
}

// escape makes a label value safe. Nothing this driver produces contains a quote or a newline, and
// the day something does, a malformed exposition is a monitoring outage rather than a bad label.
func escape(value string) string {
	replacer := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`)
	return replacer.Replace(value)
}
