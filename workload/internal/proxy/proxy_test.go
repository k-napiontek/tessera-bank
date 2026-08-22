package proxy_test

import (
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/proxy"
)

func upstream(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return server
}

func inFront(t *testing.T, upstreamURL string) *proxy.Proxy {
	t.Helper()
	forwarder, err := proxy.Start("127.0.0.1:0", upstreamURL)
	if err != nil {
		t.Fatalf("starting the proxy: %v", err)
	}
	t.Cleanup(func() { _ = forwarder.Close() })
	return forwarder
}

func get(t *testing.T, url string) (int, string) {
	t.Helper()
	response, err := http.Get(url)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("reading the body: %v", err)
	}
	return response.StatusCode, string(body)
}

func TestWithNoDelayItIsATransparentForwarder(t *testing.T) {
	var seen struct {
		key    string
		method string
		path   string
	}
	server := upstream(t, func(w http.ResponseWriter, r *http.Request) {
		seen.key = r.Header.Get("Idempotency-Key")
		seen.method = r.Method
		seen.path = r.URL.Path
		w.Header().Set("Idempotency-Replayed", "true")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"transferRef":"TR-1"}`))
	})
	forwarder := inFront(t, server.URL)

	request, err := http.NewRequest(http.MethodPost, "http://"+forwarder.Addr()+"/v1/transfers", nil)
	if err != nil {
		t.Fatalf("building the request: %v", err)
	}
	request.Header.Set("Idempotency-Key", "wl-20260822-0000000001-createTransfer")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("through the proxy: %v", err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)

	if response.StatusCode != http.StatusCreated {
		t.Errorf("status %d, want 201", response.StatusCode)
	}
	if string(body) != `{"transferRef":"TR-1"}` {
		t.Errorf("body %q came through changed", body)
	}
	// The header the driver and the ledger's own filter both read since F-71 closed. A proxy that
	// dropped it would turn every replay into a post, silently, in both counters at once.
	if response.Header.Get("Idempotency-Replayed") != "true" {
		t.Error("the proxy dropped Idempotency-Replayed on the way back")
	}
	if seen.key != "wl-20260822-0000000001-createTransfer" {
		t.Errorf("the upstream saw the key as %q", seen.key)
	}
	if seen.method != http.MethodPost || seen.path != "/v1/transfers" {
		t.Errorf("the upstream saw %s %s", seen.method, seen.path)
	}
}

func TestTheDelayIsAppliedInFrontOfTheUpstreamRatherThanBehindIt(t *testing.T) {
	// The whole point of the condition. The delay has to land where the upstream cannot see it, so
	// that the ledger's own latency histogram stays flat while the customer waits - which is the
	// signature SCN-SLOW-DEPENDENCY exists to demonstrate.
	var upstreamSpent atomic.Int64
	server := upstream(t, func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		w.WriteHeader(http.StatusOK)
		upstreamSpent.Store(int64(time.Since(started)))
	})
	forwarder := inFront(t, server.URL)

	const delay = 120 * time.Millisecond
	forwarder.SetDelay(delay)

	started := time.Now()
	status, _ := get(t, "http://"+forwarder.Addr()+"/v1/accounts/AC-1")
	elapsed := time.Since(started)

	if status != http.StatusOK {
		t.Errorf("status %d, want 200", status)
	}
	if elapsed < delay {
		t.Errorf("the customer waited %s, and the declared delay is %s", elapsed, delay)
	}
	if spent := time.Duration(upstreamSpent.Load()); spent > delay/2 {
		t.Errorf("the upstream spent %s in the handler, so the delay landed behind it", spent)
	}
}

func TestTheDelayCanBeSetAndClearedWhileRequestsAreInFlight(t *testing.T) {
	// A condition is applied part way into a run and reverted part way out of it, against a driver
	// that never stops sending. A proxy that had to be restarted to change its delay would make the
	// injection a restart, and a restart is a different condition.
	server := upstream(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) })
	forwarder := inFront(t, server.URL)

	url := "http://" + forwarder.Addr() + "/v1/accounts/AC-1"

	quick := time.Now()
	get(t, url)
	before := time.Since(quick)

	forwarder.SetDelay(100 * time.Millisecond)
	slow := time.Now()
	get(t, url)
	during := time.Since(slow)

	forwarder.SetDelay(0)
	again := time.Now()
	get(t, url)
	after := time.Since(again)

	if during < 100*time.Millisecond {
		t.Errorf("with the delay set the request took %s", during)
	}
	if before > 50*time.Millisecond || after > 50*time.Millisecond {
		t.Errorf("without the delay the requests took %s and %s", before, after)
	}
	if forwarder.Delay() != 0 {
		t.Errorf("Delay() reports %s after being cleared", forwarder.Delay())
	}
}

func TestAnUpstreamThatIsNotThereIsAGatewayFailureRatherThanAPanic(t *testing.T) {
	// The partial-outage condition suspends the ledger while the proxy stays up. What the gateway
	// must see is a failure it can classify, not a dropped connection that reads as a broken driver.
	server := upstream(t, func(w http.ResponseWriter, r *http.Request) {})
	address := server.URL
	server.Close()

	forwarder := inFront(t, address)
	status, _ := get(t, "http://"+forwarder.Addr()+"/v1/accounts/AC-1")
	if status < 500 {
		t.Errorf("status %d, want a 5xx the caller can classify as unknown", status)
	}
}

func TestItRefusesAnUpstreamItCannotAddress(t *testing.T) {
	if _, err := proxy.Start("127.0.0.1:0", "not-a-url"); err == nil {
		t.Fatal("started in front of an upstream that is not an address")
	}
	if _, err := proxy.Start("127.0.0.1:0", ""); err == nil {
		t.Fatal("started in front of no upstream at all")
	}
}
