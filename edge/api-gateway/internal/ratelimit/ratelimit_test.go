package ratelimit_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/auth"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/ratelimit"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

// clock is a hand-wound clock, so a refill test does not have to sleep for it.
type clock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *clock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

func newLimiter(rate float64, burst int, c *clock) *ratelimit.Limiter {
	return ratelimit.New(ratelimit.Settings{PerSecond: rate, Burst: burst, Now: c.Now})
}

func fixedClock() *clock {
	return &clock{now: time.Date(2026, 8, 19, 9, 0, 0, 0, time.UTC)}
}

func route(class, scope string) routing.Route {
	return routing.Route{Method: http.MethodPost, Path: "/transfers", Scope: scope, Class: class}
}

// send drives one request through the limiter with a given subject and route.
func send(t *testing.T, limiter *ratelimit.Limiter, subject, class string) *httptest.ResponseRecorder {
	t.Helper()

	handler := correlation.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx := auth.WithPrincipal(r.Context(), auth.Principal{Subject: subject, Scopes: []string{routing.ScopeWrite}})
		ctx = routing.WithRoute(ctx, route(class, routing.ScopeWrite))
		ratelimit.Middleware(limiter)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusOK)
		})).ServeHTTP(w, r.WithContext(ctx))
	}))

	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/transfers", nil))
	return recorder
}

func TestABurstIsAllowedAndThenTheLimitBites(t *testing.T) {
	limiter := newLimiter(1, 3, fixedClock())

	for i := 1; i <= 3; i++ {
		if response := send(t, limiter, "CUST-1", "transfers.create"); response.Code != http.StatusOK {
			t.Fatalf("request %d = %d, want 200 within the burst", i, response.Code)
		}
	}

	response := send(t, limiter, "CUST-1", "transfers.create")
	if response.Code != http.StatusTooManyRequests {
		t.Fatalf("the fourth request = %d, want 429", response.Code)
	}
}

func TestARefusalTellsTheClientWhenToComeBack(t *testing.T) {
	limiter := newLimiter(2, 1, fixedClock())
	send(t, limiter, "CUST-1", "transfers.create")

	response := send(t, limiter, "CUST-1", "transfers.create")

	retryAfter := response.Header().Get("Retry-After")
	if retryAfter == "" {
		t.Fatal("a 429 without Retry-After leaves the client to guess, and clients guess badly")
	}
	seconds, err := strconv.Atoi(retryAfter)
	if err != nil {
		t.Fatalf("Retry-After = %q, want whole seconds: %v", retryAfter, err)
	}
	// RFC 9110 allows a delta in seconds; zero would invite an immediate retry, which is what the
	// limit exists to prevent.
	if seconds < 1 {
		t.Errorf("Retry-After = %d, want at least 1", seconds)
	}

	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("body is not JSON: %v", err)
	}
	if body["type"] != "https://problems.tesserabank.example/rate-limited" {
		t.Errorf("type = %v", body["type"])
	}
	if got := response.Header().Get("Content-Type"); got != "application/problem+json" {
		t.Errorf("content type = %q", got)
	}
}

func TestTheBucketRefillsOverTime(t *testing.T) {
	c := fixedClock()
	limiter := newLimiter(2, 2, c)

	send(t, limiter, "CUST-1", "transfers.create")
	send(t, limiter, "CUST-1", "transfers.create")
	if response := send(t, limiter, "CUST-1", "transfers.create"); response.Code != http.StatusTooManyRequests {
		t.Fatalf("the burst was not exhausted: %d", response.Code)
	}

	c.advance(500 * time.Millisecond) // two per second: half a second buys one token

	if response := send(t, limiter, "CUST-1", "transfers.create"); response.Code != http.StatusOK {
		t.Errorf("after a refill = %d, want 200", response.Code)
	}
}

func TestRefillingNeverExceedsTheBurst(t *testing.T) {
	c := fixedClock()
	limiter := newLimiter(10, 2, c)

	send(t, limiter, "CUST-1", "transfers.create")
	c.advance(time.Hour)

	// An idle hour must not buy an hour's worth of requests to spend at once. The burst is the cap
	// on the damage a single caller can do in one instant.
	for i := 1; i <= 2; i++ {
		if response := send(t, limiter, "CUST-1", "transfers.create"); response.Code != http.StatusOK {
			t.Fatalf("request %d after the idle hour = %d, want 200", i, response.Code)
		}
	}
	if response := send(t, limiter, "CUST-1", "transfers.create"); response.Code != http.StatusTooManyRequests {
		t.Errorf("a third immediate request = %d, want 429 - the bucket refilled past its burst", response.Code)
	}
}

func TestOneCustomerCannotExhaustAnother(t *testing.T) {
	limiter := newLimiter(1, 1, fixedClock())

	send(t, limiter, "CUST-1", "transfers.create")
	if response := send(t, limiter, "CUST-1", "transfers.create"); response.Code != http.StatusTooManyRequests {
		t.Fatalf("the first customer was not limited: %d", response.Code)
	}

	if response := send(t, limiter, "CUST-2", "transfers.create"); response.Code != http.StatusOK {
		t.Errorf("the second customer = %d, want 200 - one caller must not spend another's budget", response.Code)
	}
}

func TestReadingAndWritingAreLimitedSeparately(t *testing.T) {
	limiter := newLimiter(1, 1, fixedClock())

	send(t, limiter, "CUST-1", "transfers.create")
	if response := send(t, limiter, "CUST-1", "transfers.create"); response.Code != http.StatusTooManyRequests {
		t.Fatalf("the write route was not limited: %d", response.Code)
	}

	// A customer refreshing a balance must not use up the budget that lets them send money.
	if response := send(t, limiter, "CUST-1", "accounts.balance"); response.Code != http.StatusOK {
		t.Errorf("a different route = %d, want 200", response.Code)
	}
}

func TestAnUnauthenticatedRequestIsRefusedRatherThanShareOneBucket(t *testing.T) {
	limiter := newLimiter(1, 1, fixedClock())

	recorder := httptest.NewRecorder()
	handler := correlation.Middleware(ratelimit.Middleware(limiter)(http.HandlerFunc(
		func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })))
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/transfers", nil))

	// Keying an unauthenticated request on anything else would put every such request in one
	// bucket, so one caller could deny the endpoint to everybody. The chain authenticates first;
	// this is the assertion that says so out loud.
	if recorder.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", recorder.Code)
	}
}

func TestIdleBucketsAreForgotten(t *testing.T) {
	c := fixedClock()
	limiter := ratelimit.New(ratelimit.Settings{PerSecond: 1, Burst: 1, Now: c.Now, IdleTimeout: time.Minute})

	send(t, limiter, "CUST-1", "transfers.create")
	send(t, limiter, "CUST-2", "transfers.create")
	if got := limiter.Tracked(); got != 2 {
		t.Fatalf("tracked = %d, want 2", got)
	}

	c.advance(2 * time.Minute)
	send(t, limiter, "CUST-3", "transfers.create")

	// Every distinct subject would otherwise be remembered forever, which is a slow leak that a
	// caller with many tokens can make a fast one.
	if got := limiter.Tracked(); got != 1 {
		t.Errorf("tracked = %d after the idle timeout, want 1", got)
	}
}

func TestConcurrentCallersGetExactlyTheBurst(t *testing.T) {
	limiter := newLimiter(0.0001, 20, fixedClock())

	var (
		wg      sync.WaitGroup
		mu      sync.Mutex
		allowed int
	)
	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if send(t, limiter, "CUST-1", "transfers.create").Code == http.StatusOK {
				mu.Lock()
				allowed++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()

	// Run under -race, this is also the test that the bucket map is not being written from several
	// goroutines at once.
	if allowed != 20 {
		t.Errorf("allowed %d of 200 concurrent requests, want exactly the burst of 20", allowed)
	}
}
