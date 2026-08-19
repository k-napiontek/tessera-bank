package metrics_test

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/metrics"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

// record drives one request that ends in the given status. A class names the route the request is
// to be treated as having matched; an empty one stands for a request refused before it was routed.
func record(m *metrics.Metrics, status int, class string) {
	handler := m.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if class != "" {
			routing.Record(r.Context(), routing.Route{
				Method: http.MethodPost, Path: "/transfers", Scope: routing.ScopeWrite, Class: class,
			})
		}
		w.WriteHeader(status)
	}))

	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/transfers", nil))
}

func scrape(t *testing.T, m *metrics.Metrics) string {
	t.Helper()
	recorder := httptest.NewRecorder()
	m.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("scrape = %d", recorder.Code)
	}
	return recorder.Body.String()
}

func TestARequestIsCountedAndTimed(t *testing.T) {
	m := metrics.New()

	record(m, http.StatusCreated, "transfers.create")

	body := scrape(t, m)
	if !strings.Contains(body, `tessera_gateway_requests_total{method="POST",route="transfers.create",status="201"} 1`) {
		t.Errorf("the request was not counted:\n%s", body)
	}
	if !strings.Contains(body, `tessera_gateway_request_duration_seconds_count{route="transfers.create"} 1`) {
		t.Errorf("the request was not timed:\n%s", body)
	}
}

func TestTheRouteLabelIsTheClassAndNeverThePath(t *testing.T) {
	m := metrics.New()

	record(m, http.StatusOK, "accounts.get")

	body := scrape(t, m)
	// A path carries an account reference, and one time series per account is how a monitoring
	// system is taken down by the traffic it is supposed to be watching.
	if strings.Contains(body, "ACC-") || strings.Contains(body, `route="/accounts`) {
		t.Errorf("a path reached a metric label:\n%s", body)
	}
}

func TestARequestRefusedBeforeRoutingIsMeasuredAsUnrouted(t *testing.T) {
	m := metrics.New()

	record(m, http.StatusUnauthorized, "")

	body := scrape(t, m)
	if !strings.Contains(body, `route="unrouted"`) {
		t.Errorf("an unrouted request was not labelled as such:\n%s", body)
	}
}

func TestTheEdgesOwnRefusalsAreCountedSeparately(t *testing.T) {
	m := metrics.New()

	record(m, http.StatusUnauthorized, "")
	record(m, http.StatusForbidden, "transfers.create")
	record(m, http.StatusTooManyRequests, "transfers.create")
	record(m, http.StatusRequestEntityTooLarge, "transfers.create")
	record(m, http.StatusNotFound, "")

	body := scrape(t, m)
	for _, reason := range []string{"unauthenticated", "forbidden", "rate_limited", "payload_too_large", "no_route"} {
		want := `tessera_gateway_refusals_total{reason="` + reason + `"} 1`
		if !strings.Contains(body, want) {
			t.Errorf("missing %s:\n%s", want, body)
		}
	}
}

func TestALedgerRefusalIsNotCountedAsTheEdgesOwn(t *testing.T) {
	m := metrics.New()

	// 422 insufficient funds, decided by the ledger and relayed. Counting it as a refusal by the
	// edge would make "is the gateway rejecting traffic" unanswerable.
	record(m, http.StatusUnprocessableEntity, "transfers.create")

	body := scrape(t, m)
	if strings.Contains(body, "tessera_gateway_refusals_total{") {
		t.Errorf("a ledger refusal was counted as the edge's:\n%s", body)
	}
	if !strings.Contains(body, `status="422"`) {
		t.Errorf("the relayed status was not counted:\n%s", body)
	}
}

func TestUpstreamFailuresAreCountedByKind(t *testing.T) {
	m := metrics.New()

	record(m, http.StatusGatewayTimeout, "transfers.create")
	record(m, http.StatusBadGateway, "accounts.get")

	body := scrape(t, m)
	for _, kind := range []string{"timeout", "unusable"} {
		want := `tessera_gateway_upstream_failures_total{kind="` + kind + `"} 1`
		if !strings.Contains(body, want) {
			t.Errorf("missing %s:\n%s", want, body)
		}
	}
}

func TestTwoGatewaysDoNotShareARegistry(t *testing.T) {
	first := metrics.New()
	second := metrics.New()

	record(first, http.StatusOK, "accounts.get")

	// A package-level registry would make this panic on the second registration, or leak counts
	// from one test into the next.
	if strings.Contains(scrape(t, second), `tessera_gateway_requests_total{`) {
		t.Error("a second gateway sees the first one's counts")
	}
}

func TestTheProcessIsMeasuredToo(t *testing.T) {
	m := metrics.New()

	body := scrape(t, m)
	// Goroutine count and heap size are the first two numbers anybody asks for when an edge process
	// starts behaving strangely.
	if !strings.Contains(body, "go_goroutines") {
		t.Errorf("no runtime metrics are exposed:\n%s", body)
	}
}
