package proxy_test

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/proxy"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// flaky fails the first failures attempts at the transport, then answers 200.
func flaky(failures int, calls *atomic.Int64) http.RoundTripper {
	return roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		if calls.Add(1) <= int64(failures) {
			// A connection refused by a ledger that is restarting: the request was never seen, so
			// nothing downstream has happened.
			return nil, errors.New("connect: connection refused")
		}
		return &http.Response{
			StatusCode: http.StatusOK,
			Header:     http.Header{"Content-Type": []string{"application/json"}},
			Body:       io.NopCloser(strings.NewReader(`{"ok":true}`)),
			Request:    r,
		}, nil
	})
}

func retrying(t *testing.T, attempts int, transport http.RoundTripper) proxy.Settings {
	t.Helper()
	target, err := url.Parse("http://ledger-core.internal/v1")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	s := settings(target)
	s.Attempts = attempts
	s.Transport = transport
	return s
}

func TestAReadIsRetriedWhenTheLedgerWasNeverReached(t *testing.T) {
	var calls atomic.Int64
	s := retrying(t, 2, flaky(1, &calls))

	response := through(t, s, httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))

	if response.Code != http.StatusOK {
		t.Errorf("status = %d, want 200 after the retry", response.Code)
	}
	if calls.Load() != 2 {
		t.Errorf("attempts = %d, want 2", calls.Load())
	}
}

func TestATransferWithoutAnIdempotencyKeyIsNeverRetried(t *testing.T) {
	var calls atomic.Int64
	s := retrying(t, 3, flaky(1, &calls))

	response := through(t, s, httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader("{}")))

	// This is the rule the whole retry design exists to protect. A connection error can be raised
	// after the ledger has read the request, so replaying a transfer that carries no idempotency
	// key is how a customer is debited twice - and no amount of logging afterwards undoes it.
	if calls.Load() != 1 {
		t.Errorf("attempts = %d, want exactly 1", calls.Load())
	}
	if response.Code != http.StatusBadGateway {
		t.Errorf("status = %d, want 502", response.Code)
	}
}

func TestATransferWithAnIdempotencyKeyIsRetried(t *testing.T) {
	var calls atomic.Int64
	s := retrying(t, 2, flaky(1, &calls))

	request := httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader(`{"amount":1}`))
	request.Header.Set("Idempotency-Key", "6f1c9b5e-2d3a-4f8b-9c0d-1e2f3a4b5c6d")

	response := through(t, s, request)

	// The key is what makes a replay safe: the ledger resolves it and returns the original answer
	// rather than posting a second transfer.
	if calls.Load() != 2 {
		t.Errorf("attempts = %d, want 2", calls.Load())
	}
	if response.Code != http.StatusOK {
		t.Errorf("status = %d, want 200", response.Code)
	}
}

func TestTheRequestBodySurvivesARetry(t *testing.T) {
	var calls atomic.Int64
	var second string

	transport := roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		body, _ := io.ReadAll(r.Body)
		if calls.Add(1) == 1 {
			return nil, errors.New("connect: connection refused")
		}
		second = string(body)
		return &http.Response{
			StatusCode: http.StatusOK,
			Header:     http.Header{},
			Body:       io.NopCloser(strings.NewReader("{}")),
			Request:    r,
		}, nil
	})

	request := httptest.NewRequest(http.MethodPost, "/transfers", strings.NewReader(`{"amount":1234}`))
	request.Header.Set("Idempotency-Key", "6f1c9b5e-2d3a-4f8b-9c0d-1e2f3a4b5c6d")
	through(t, retrying(t, 2, transport), request)

	// A body streamed from the client can only be read once; the second attempt would send an empty
	// one, and the ledger would reject a valid transfer as malformed.
	if second != `{"amount":1234}` {
		t.Errorf("the second attempt sent %q", second)
	}
}

func TestTheRetryBudgetIsBounded(t *testing.T) {
	var calls atomic.Int64
	s := retrying(t, 2, flaky(99, &calls))

	response := through(t, s, httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))

	// An edge that keeps trying multiplies the load on a dependency that is already failing.
	if calls.Load() != 2 {
		t.Errorf("attempts = %d, want the configured 2", calls.Load())
	}
	if response.Code != http.StatusBadGateway {
		t.Errorf("status = %d, want 502", response.Code)
	}
}

func TestATimeoutIsNotRetried(t *testing.T) {
	var calls atomic.Int64
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		select {
		case <-time.After(2 * time.Second):
		case <-r.Context().Done():
		}
	}))
	defer slow.Close()

	target, err := url.Parse(slow.URL)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	s := settings(target)
	s.Attempts = 3
	s.Timeout = 100 * time.Millisecond

	response := through(t, s, httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))

	// A timeout means the ledger has the request and is struggling with it. Sending it again is
	// how a slow dependency is turned into a failed one - the retry storm arrives exactly when the
	// dependency can least afford it.
	if calls.Load() != 1 {
		t.Errorf("attempts = %d, want 1 - a timeout must not be retried", calls.Load())
	}
	if response.Code != http.StatusGatewayTimeout {
		t.Errorf("status = %d, want 504", response.Code)
	}
}

func TestAnAnswerIsNeverRetriedHoweverBadItIs(t *testing.T) {
	var calls atomic.Int64
	transport := roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		return &http.Response{
			StatusCode: http.StatusInternalServerError,
			Header:     http.Header{"Content-Type": []string{"application/problem+json"}},
			Body:       io.NopCloser(strings.NewReader(`{"type":"https://problems.tesserabank.example/internal"}`)),
			Request:    r,
		}, nil
	})

	response := through(t, retrying(t, 3, transport), httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001", nil))

	// A 500 is an answer. The ledger has decided something, and the gateway does not know what -
	// so it relays the decision rather than asking again and hoping for a different one.
	if calls.Load() != 1 {
		t.Errorf("attempts = %d, want 1", calls.Load())
	}
	if response.Code != http.StatusInternalServerError {
		t.Errorf("status = %d, want the ledger's 500 relayed", response.Code)
	}
}

func TestALargeBodyIsRelayedInFull(t *testing.T) {
	l := newLedger(t)
	l.reply = strings.Repeat("c", 3000)
	s := settings(l.url(t, ""))
	s.MaxResponseBytes = 4096

	response := through(t, s, httptest.NewRequest(http.MethodGet, "/accounts/ACC-000001/statement", nil))

	// The response body is read through a context that the proxy must not cancel until the last
	// byte is in hand. Cancelling it early truncates a large body and leaves a 200 on the wire.
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d", response.Code)
	}
	if len(response.Body.String()) != 3000 {
		t.Errorf("relayed %d bytes, want 3000", len(response.Body.String()))
	}
}
