package client_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/identity"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
)

// estate is a stand-in for the gateway that answers however a test needs and records what it was
// asked. Not a mock of the ledger: the only thing under test here is what the driver does with an
// answer, and the answers a real gateway gives are the five statuses below.
type estate struct {
	handler http.HandlerFunc

	mu       sync.Mutex
	requests []*http.Request
	keys     []string
	tokens   []string
}

func (e *estate) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	e.mu.Lock()
	e.requests = append(e.requests, r)
	e.keys = append(e.keys, r.Header.Get("Idempotency-Key"))
	e.tokens = append(e.tokens, r.Header.Get("Authorization"))
	e.mu.Unlock()
	e.handler(w, r)
}

func (e *estate) count() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return len(e.requests)
}

func (e *estate) keysSent() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([]string(nil), e.keys...)
}

func sender(t *testing.T, answers http.HandlerFunc, attempts int) (*client.Sender, *estate, func()) {
	t.Helper()
	fake := &estate{handler: answers}
	server := httptest.NewServer(fake)

	issued, err := identity.Generate(identity.Settings{
		Issuer: "https://issuer.tesserabank.example", Audience: "tessera-bank-ledger",
		Scopes: identity.Scopes(), TTL: time.Hour,
	})
	if err != nil {
		t.Fatalf("generating an issuer: %v", err)
	}
	sending, err := client.New(client.Settings{
		Origin:   server.URL,
		Timeout:  2 * time.Second,
		Attempts: attempts,
		Wallet:   identity.NewWallet(issued),
	})
	if err != nil {
		t.Fatalf("building a sender: %v", err)
	}
	return sending, fake, server.Close
}

func transfer(t *testing.T) client.Request {
	t.Helper()
	built, err := client.Build(action("createTransfer"), date(t), 42, "PLN", populated())
	if err != nil {
		t.Fatalf("building a transfer: %v", err)
	}
	return built
}

func answering(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		if body != "" {
			_, _ = w.Write([]byte(body))
		}
	}
}

func TestEachStatusLandsInTheColumnItBelongsIn(t *testing.T) {
	// The five classes exist because a bank cannot be measured with two. 429 apart from 4xx: a
	// refusal is a control working. 5xx apart from 4xx: the request may have been applied.
	cases := []struct {
		status int
		want   client.Outcome
	}{
		{http.StatusCreated, client.Posted},
		{http.StatusOK, client.Replayed},
		{http.StatusUnprocessableEntity, client.Rejected},
		{http.StatusConflict, client.Rejected},
		{http.StatusNotFound, client.Rejected},
		{http.StatusTooManyRequests, client.Refused},
		{http.StatusInternalServerError, client.Unknown},
		{http.StatusBadGateway, client.Unknown},
	}
	for _, c := range cases {
		t.Run(http.StatusText(c.status), func(t *testing.T) {
			sending, _, stop := sender(t, answering(c.status, `{}`), 1)
			defer stop()

			result := sending.Send(context.Background(), transfer(t), time.Now())
			if result.Outcome != c.want {
				t.Errorf("%d is %s, want %s", c.status, result.Outcome, c.want)
			}
		})
	}
}

func TestARequestThatNeverArrivesIsUnknownRatherThanFailed(t *testing.T) {
	// The connection dies with the request in flight. It may have been applied; nothing here can
	// tell, and a driver that called it a failure would report a bank that lost a payment it in
	// fact posted.
	sending, _, stop := sender(t, func(w http.ResponseWriter, _ *http.Request) {
		hijacked, _, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Errorf("hijacking: %v", err)
			return
		}
		_ = hijacked.Close()
	}, 1)
	defer stop()

	result := sending.Send(context.Background(), transfer(t), time.Now())
	if result.Outcome != client.Unknown {
		t.Errorf("a dropped connection is %s", result.Outcome)
	}
	if result.Err == nil {
		t.Error("an unknown outcome carries no error to log")
	}
}

func TestARetryOfALostRequestCarriesTheKeyTheFirstAttemptUsed(t *testing.T) {
	// This is the test the whole package hangs on. A driver that mints a fresh key per HTTP call
	// double-spends under packet loss and reports success twice, because both requests answer 201.
	var seen int
	sending, fake, stop := sender(t, func(w http.ResponseWriter, _ *http.Request) {
		seen++
		if seen == 1 {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		// What the ledger answers to a replayed key: the original result, and 200 rather than 201.
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"transferRef":"TB202608310000000009"}`))
	}, 3)
	defer stop()

	result := sending.Send(context.Background(), transfer(t), time.Now())

	if result.Outcome != client.Replayed {
		t.Errorf("the retry came back %s, and a replayed key is the ledger refusing to post twice", result.Outcome)
	}
	if result.Attempts != 2 {
		t.Errorf("took %d attempts", result.Attempts)
	}
	keys := fake.keysSent()
	if len(keys) != 2 || keys[0] != keys[1] {
		t.Errorf("the two attempts carried %v", keys)
	}
	if keys[0] == "" {
		t.Error("a money-moving request went out with no idempotency key")
	}
	if length := len(keys[0]); length < 16 || length > 64 {
		t.Errorf("the key is %d characters and the contract permits 16 to 64: %q", length, keys[0])
	}
}

func TestARefusalIsNeverRetried(t *testing.T) {
	// Retrying into a rate limiter converts a working control into a stampede, and measures the
	// retry loop rather than the bank. An open model does not re-offer a refused request at all: it
	// goes on to the next scheduled one.
	sending, fake, stop := sender(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Retry-After", "3")
		w.WriteHeader(http.StatusTooManyRequests)
	}, 4)
	defer stop()

	result := sending.Send(context.Background(), transfer(t), time.Now())

	if result.Outcome != client.Refused {
		t.Errorf("a 429 is %s", result.Outcome)
	}
	if fake.count() != 1 {
		t.Errorf("a refusal was sent %d times", fake.count())
	}
	if result.RetryAfter != 3*time.Second {
		t.Errorf("Retry-After recorded as %s", result.RetryAfter)
	}
}

func TestARejectionIsNeverRetriedEither(t *testing.T) {
	// The ledger understood the request and refused it. Sending it again asks the same question and
	// gets the same answer, and inflates the offered load with traffic the schedule never planned.
	sending, fake, stop := sender(t, answering(http.StatusUnprocessableEntity, `{"type":"insufficient-funds"}`), 4)
	defer stop()

	if result := sending.Send(context.Background(), transfer(t), time.Now()); result.Outcome != client.Rejected {
		t.Errorf("outcome %s", result.Outcome)
	}
	if fake.count() != 1 {
		t.Errorf("a rejection was sent %d times", fake.count())
	}
}

func TestLatencyIsMeasuredFromTheIntendedSendTimeAndNotFromTheActualOne(t *testing.T) {
	// The point of ADR 0016, at the last possible moment. A driver that starts its stopwatch when
	// it manages to send has already subtracted its own queueing from the number - the estate looks
	// fastest exactly when the driver is furthest behind.
	fake := &estate{handler: answering(http.StatusCreated, `{}`)}
	server := httptest.NewServer(fake)
	defer server.Close()

	frozen := time.Date(2026, 8, 31, 12, 0, 5, 0, time.UTC)
	issued, err := identity.Generate(identity.Settings{
		Issuer: "i", Audience: "a", Scopes: identity.Scopes(), TTL: time.Hour,
	})
	if err != nil {
		t.Fatalf("generating: %v", err)
	}
	sending, err := client.New(client.Settings{
		Origin: server.URL, Attempts: 1, Wallet: identity.NewWallet(issued),
		Now: func() time.Time { return frozen },
	})
	if err != nil {
		t.Fatalf("building: %v", err)
	}

	// Scheduled five seconds before the clock this run is reading: the driver was late.
	intended := frozen.Add(-5 * time.Second)
	result := sending.Send(context.Background(), transfer(t), intended)

	if result.Latency != 5*time.Second {
		t.Errorf("latency is %s, and the request was five seconds late before it left", result.Latency)
	}
}

func TestTheRequestPresentsTheSubjectsOwnToken(t *testing.T) {
	// One token per subject is what keeps the gateway's rate limiter measuring what it exists to
	// measure: it buckets on subject and route class, so a population behind one token is one
	// caller being throttled.
	sending, fake, stop := sender(t, answering(http.StatusCreated, `{}`), 1)
	defer stop()

	sending.Send(context.Background(), transfer(t), time.Now())

	fake.mu.Lock()
	defer fake.mu.Unlock()
	if len(fake.tokens) != 1 || !strings.HasPrefix(fake.tokens[0], "Bearer ") {
		t.Fatalf("the authorization header was %q", fake.tokens)
	}
	if strings.Count(fake.tokens[0], ".") != 2 {
		t.Errorf("the bearer token is not a JWT: %q", fake.tokens[0])
	}
}

func TestTheRunLearnsTheReferencesTheLedgerAllocated(t *testing.T) {
	// The ledger allocates its own transfer and hold references, so reading one back is the only
	// way a later getTransfer names something that exists.
	sending, _, stop := sender(t, answering(http.StatusCreated,
		`{"transferRef":"TB202608310000000123","status":"POSTED"}`), 1)
	defer stop()

	result := sending.Send(context.Background(), transfer(t), time.Now())
	if result.Transfer.Ref != "TB202608310000000123" {
		t.Errorf("learned %q", result.Transfer.Ref)
	}
}

func TestAPlacedHoldIsRememberedWithWhatItReserved(t *testing.T) {
	// A capture may not exceed the held amount, so the amount travels with the reference.
	built, err := client.Build(action("placeHold"), date(t), 11, "PLN", populated())
	if err != nil {
		t.Fatalf("building a hold: %v", err)
	}
	sending, _, stop := sender(t, answering(http.StatusCreated,
		`{"holdRef":"HL202608310000000055","accountRef":"TB0000000000000A",`+
			`"amount":{"amountMinor":12345,"currency":"PLN"},"status":"ACTIVE"}`), 1)
	defer stop()

	result := sending.Send(context.Background(), built, time.Now())
	if result.Hold.Ref != "HL202608310000000055" {
		t.Fatalf("learned hold %q", result.Hold.Ref)
	}
	if result.Hold.Amount != (money.Amount{Minor: 12345, Currency: "PLN"}) {
		t.Errorf("learned amount %v", result.Hold.Amount)
	}
}

func TestABodyTheDriverCannotReadIsNotAFailure(t *testing.T) {
	// The request was answered. What it means is decided by the status, and an unreadable body
	// simply teaches the run nothing - it must not turn a posted transfer into an unknown one.
	sending, _, stop := sender(t, answering(http.StatusCreated, `<html>a proxy said hello</html>`), 1)
	defer stop()

	result := sending.Send(context.Background(), transfer(t), time.Now())
	if result.Outcome != client.Posted {
		t.Errorf("outcome %s", result.Outcome)
	}
	if result.Transfer.Ref != "" {
		t.Errorf("invented a reference out of unreadable bytes: %q", result.Transfer.Ref)
	}
}

func TestASenderWithNowhereToSendIsRefusedAtConstruction(t *testing.T) {
	if _, err := client.New(client.Settings{Wallet: identity.NewWallet(nil)}); err == nil {
		t.Error("built a sender with no origin")
	}
	if _, err := client.New(client.Settings{Origin: "http://localhost:1"}); err == nil {
		t.Error("built a sender with no wallet, so every request would go out unauthenticated")
	}
}
