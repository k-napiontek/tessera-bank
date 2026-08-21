package metrics_test

import (
	"net/http"
	"net/http/httptest"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/metrics"
)

func scrape(t *testing.T, registry *metrics.Registry) string {
	t.Helper()
	var out strings.Builder
	if err := registry.Write(&out); err != nil {
		t.Fatalf("Write: %v", err)
	}
	return out.String()
}

func request(operation string) client.Request {
	return client.Request{
		Operation: operation,
		Method:    "POST",
		Template:  "/transfers",
		Path:      "/accounts/TB0000000000000A/balance",
		Subject:   "CU0000000001",
	}
}

func TestEveryOutcomeIsCountedUnderItsOwnName(t *testing.T) {
	// The five columns are the whole point of the driver's accounting. A registry that folded any
	// two of them would make the run irreconcilable against the ledger's own counter.
	registry := metrics.New()
	for _, outcome := range client.Outcomes() {
		registry.Result(request("createTransfer"), client.Result{Outcome: outcome, Latency: 10 * time.Millisecond})
	}

	text := scrape(t, registry)
	for _, outcome := range client.Outcomes() {
		line := `tessera_workload_requests_total{operation="createTransfer",outcome="` + outcome.String() + `"} 1`
		if !strings.Contains(text, line) {
			t.Errorf("no line for %s:\n%s", outcome, text)
		}
	}
}

func TestNoLabelCarriesAnAccountReference(t *testing.T) {
	// One series per account in a 1.2 million customer population is how a monitoring system is
	// taken down, and it is discovered when the monitoring is needed most. The gateway keeps a
	// bounded route class for the same reason.
	registry := metrics.New()
	registry.Result(request("getBalance"), client.Result{Outcome: client.Replayed, Latency: time.Millisecond})
	registry.Unsent("getTransfer", "no-reference-yet")

	text := scrape(t, registry)
	if strings.Contains(text, "TB0000000000000A") {
		t.Errorf("an account reference reached a label:\n%s", text)
	}
	if strings.Contains(text, "CU0000000001") {
		t.Errorf("a customer reference reached a label:\n%s", text)
	}
}

func TestTheHistogramIsCumulativeAndItsLastBucketIsItsCount(t *testing.T) {
	// A histogram whose buckets are not cumulative parses without complaint and produces quantiles
	// that are quietly wrong, which is the shape of defect this estate keeps finding.
	registry := metrics.New()
	for _, latency := range []time.Duration{time.Millisecond, 20 * time.Millisecond, 400 * time.Millisecond, 90 * time.Second} {
		registry.Result(request("createTransfer"), client.Result{Outcome: client.Posted, Latency: latency})
	}

	text := scrape(t, registry)
	buckets := regexp.MustCompile(`tessera_workload_request_duration_seconds_bucket\{operation="createTransfer",le="([^"]+)"\} (\d+)`)
	matches := buckets.FindAllStringSubmatch(text, -1)
	if len(matches) != len(metrics.Buckets)+1 {
		t.Fatalf("%d buckets rendered, want %d", len(matches), len(metrics.Buckets)+1)
	}

	previous := 0
	for _, match := range matches {
		count, err := strconv.Atoi(match[2])
		if err != nil {
			t.Fatalf("bucket count %q: %v", match[2], err)
		}
		if count < previous {
			t.Errorf("bucket le=%s holds %d and the one below holds %d", match[1], count, previous)
		}
		previous = count
	}
	if matches[len(matches)-1][1] != "+Inf" {
		t.Errorf("the last bucket is le=%s", matches[len(matches)-1][1])
	}
	if previous != 4 {
		t.Errorf("+Inf holds %d of 4 observations", previous)
	}
	if !strings.Contains(text, `tessera_workload_request_duration_seconds_count{operation="createTransfer"} 4`) {
		t.Errorf("the count line is missing:\n%s", text)
	}
}

func TestALatencyBeyondTheLastBoundaryStillCounts(t *testing.T) {
	// A run measured from its intended send time carries the driver's own lag as well as the
	// estate's, so the tail is longer than a gateway's. An observation that fell off the end would
	// silently improve every percentile.
	registry := metrics.New()
	registry.Result(request("createTransfer"), client.Result{Outcome: client.Posted, Latency: 5 * time.Minute})

	text := scrape(t, registry)
	if !strings.Contains(text, `tessera_workload_request_duration_seconds_count{operation="createTransfer"} 1`) {
		t.Errorf("a five-minute request was not counted:\n%s", text)
	}
	if !strings.Contains(text, `_sum{operation="createTransfer"} 300`) {
		t.Errorf("the sum does not carry it:\n%s", text)
	}
}

func TestTheQuantileIsReadOffTheBuckets(t *testing.T) {
	registry := metrics.New()
	// Ninety fast, ten slow: the 99th belongs in the slow bucket and the median in the fast one.
	for i := 0; i < 90; i++ {
		registry.Result(request("getBalance"), client.Result{Outcome: client.Replayed, Latency: 3 * time.Millisecond})
	}
	for i := 0; i < 10; i++ {
		registry.Result(request("getBalance"), client.Result{Outcome: client.Replayed, Latency: 2 * time.Second})
	}

	if median := registry.Quantile(0.5); median > 10*time.Millisecond {
		t.Errorf("the median is %s", median)
	}
	if tail := registry.Quantile(0.99); tail < time.Second {
		t.Errorf("the 99th is %s, and one request in ten took two seconds", tail)
	}
}

func TestAnEmptyRunStillRenders(t *testing.T) {
	// A run that sent nothing is a fact worth scraping, and a registry that panicked on it would
	// take the metrics port down with it at exactly the moment somebody was asking why.
	text := scrape(t, metrics.New())
	if !strings.Contains(text, "tessera_workload_requests_total") {
		t.Errorf("no help or type lines at all:\n%s", text)
	}
	if metrics.New().Quantile(0.95) != 0 {
		t.Error("an empty histogram reported a quantile")
	}
}

func TestTheSchedulerLagIsPublishedAsItsOwnSignal(t *testing.T) {
	// The number that separates an estate under strain from a load generator that ran out of
	// machine. Without it, both look like rising latency.
	registry := metrics.New()
	registry.Lag(250 * time.Millisecond)
	registry.Lag(50 * time.Millisecond)
	registry.InFlight(12)
	registry.InFlight(4)

	text := scrape(t, registry)
	for _, line := range []string{
		"tessera_workload_schedule_lag_seconds 0.05",
		"tessera_workload_schedule_lag_seconds_max 0.25",
		"tessera_workload_in_flight 4",
		"tessera_workload_in_flight_peak 12",
	} {
		if !strings.Contains(text, line) {
			t.Errorf("missing %q:\n%s", line, text)
		}
	}
}

func TestARetryIsCountedOnceForEachExtraAttempt(t *testing.T) {
	registry := metrics.New()
	registry.Result(request("createTransfer"), client.Result{Outcome: client.Replayed, Attempts: 3})
	registry.Result(request("createTransfer"), client.Result{Outcome: client.Posted, Attempts: 1})

	if text := scrape(t, registry); !strings.Contains(text, "tessera_workload_retries_total 2") {
		t.Errorf("retries not counted:\n%s", text)
	}
}

func TestTheHandlerServesTheExpositionFormat(t *testing.T) {
	registry := metrics.New()
	registry.Result(request("getBalance"), client.Result{Outcome: client.Replayed, Latency: time.Millisecond})

	recorder := httptest.NewRecorder()
	registry.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/metrics", nil))

	if recorder.Code != http.StatusOK {
		t.Errorf("the metrics port answered %d", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/plain") {
		t.Errorf("Content-Type is %q", contentType)
	}
	if !strings.Contains(recorder.Body.String(), "# TYPE tessera_workload_requests_total counter") {
		t.Errorf("the body is not an exposition:\n%s", recorder.Body.String())
	}
}

func TestEveryMetricIsNamedTheWayTheEstateNamesMetrics(t *testing.T) {
	// tessera_<component>_<thing>_<unit>, as edge/api-gateway and services/ledger-api already do,
	// so that a dashboard built for one reads the other without translation.
	registry := metrics.New()
	registry.Result(request("createTransfer"), client.Result{Outcome: client.Posted, Latency: time.Millisecond})
	registry.Unsent("getTransfer", "no-reference-yet")

	for _, line := range strings.Split(scrape(t, registry), "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		name := line
		if index := strings.IndexAny(line, "{ "); index > 0 {
			name = line[:index]
		}
		if !strings.HasPrefix(name, "tessera_workload_") {
			t.Errorf("%q is not namespaced to this component", name)
		}
	}
}

func TestTheRegistryIsSafeUnderTheConcurrencyARunProduces(t *testing.T) {
	// Every observation arrives from the goroutine that owned the request, and there is no bound on
	// how many of those there are. Run under -race, which is how the whole module runs.
	registry := metrics.New()
	var group sync.WaitGroup
	for worker := 0; worker < 16; worker++ {
		group.Add(1)
		go func(worker int) {
			defer group.Done()
			for i := 0; i < 50; i++ {
				registry.Result(request("createTransfer"), client.Result{Outcome: client.Posted, Latency: time.Millisecond})
				registry.InFlight(worker)
				registry.Lag(time.Duration(i) * time.Millisecond)
				registry.Unsent("getTransfer", "no-reference-yet")
			}
		}(worker)
	}
	group.Wait()

	if text := scrape(t, registry); !strings.Contains(text, `outcome="posted"} 800`) {
		t.Errorf("lost observations:\n%s", text)
	}
}
