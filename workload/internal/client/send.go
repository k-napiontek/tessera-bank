package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/identity"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
)

// Outcome is what became of one logical request, retries and all.
//
// Five classes, and the boundaries between them are the whole point. A load tool has two - it
// worked or it did not - and a bank cannot be measured with two, because "the response was lost"
// and "the ledger refused it" are different facts about different systems and only one of them is a
// defect.
type Outcome int

const (
	// Posted is 201: the ledger did the thing, for the first time.
	Posted Outcome = iota
	// Replayed is 200 on a **money-moving** operation: the ledger had already done it under this
	// key and answered with the original result. Counted apart from Posted because a run's replay
	// rate is the signal that clients are timing out on a ledger answering too slowly, and folding
	// it into throughput would inflate the figure with work nobody did.
	//
	// A read that answers 200 is not a replay: it is counted as Posted, the request having done
	// what it asked. The first run of this driver against a real estate reported two and a half
	// thousand replays and twenty-four real ones, because a balance enquiry answers 200 like
	// everything else - a plausible-looking figure that meant nothing, which is the shape of defect
	// this repository keeps finding.
	Replayed
	// Rejected is 4xx other than 429: the ledger understood the request and refused it. That is the
	// bank working.
	Rejected
	// Refused is 429: the gateway's rate limiter declined to pass it on. A working control, and
	// never retried immediately - retrying into a limiter converts it into a stampede and measures
	// the retry loop rather than the bank.
	Refused
	// Unknown is 5xx, a timeout, or a connection that died: the request may or may not have been
	// applied. WP-14 found this live when a stopped gateway answered a transfer with a bare 500.
	// Keeping it out of the failure column is what lets the driver's totals reconcile against the
	// ledger's own, and folding it into either neighbour is a lie in one direction or the other.
	Unknown
)

// Outcomes is every class, in the order a report prints them.
func Outcomes() []Outcome { return []Outcome{Posted, Replayed, Rejected, Refused, Unknown} }

func (o Outcome) String() string {
	switch o {
	case Posted:
		return "posted"
	case Replayed:
		return "replayed"
	case Rejected:
		return "rejected"
	case Refused:
		return "refused"
	case Unknown:
		return "unknown"
	default:
		return "unclassified"
	}
}

// Settings configure the sender.
type Settings struct {
	// Origin is the gateway, scheme and authority: http://localhost:8081. The contract's paths are
	// served at its root - /v1 is the ledger's own prefix, which the gateway adds itself.
	Origin string
	// Timeout bounds one attempt. A driver with no timeout stops offering load the moment the
	// estate stops answering, which is the closed-model failure wearing a different hat.
	Timeout time.Duration
	// Attempts is the total number of attempts for one logical request, so 1 means no retry. Only
	// an unknown outcome is retried, and every attempt carries the same idempotency key.
	Attempts int
	// Wallet mints the token each subject presents.
	Wallet *identity.Wallet
	// Now is the clock, so a test can hold it still. Defaults to time.Now.
	Now func() time.Time
	// Transport is the round tripper, so a test can answer without a socket. Defaults to one tuned
	// for a load run; see newTransport.
	Transport http.RoundTripper
}

// Sender sends built requests to the gateway and classifies what came back.
type Sender struct {
	settings Settings
	http     *http.Client
}

// New builds a sender.
func New(settings Settings) (*Sender, error) {
	if settings.Origin == "" {
		return nil, fmt.Errorf("client: the sender needs a gateway origin")
	}
	if settings.Wallet == nil {
		return nil, fmt.Errorf("client: the sender needs a wallet to mint tokens from")
	}
	if settings.Attempts < 1 {
		settings.Attempts = 1
	}
	if settings.Timeout <= 0 {
		settings.Timeout = 5 * time.Second
	}
	if settings.Now == nil {
		settings.Now = time.Now
	}
	transport := settings.Transport
	if transport == nil {
		transport = newTransport()
	}
	return &Sender{
		settings: settings,
		http:     &http.Client{Transport: transport, Timeout: settings.Timeout},
	}, nil
}

// idleConnectionsPerHost is the size of the keep-alive pool the driver holds open to the gateway.
//
// Go's default is two. At any interesting rate that turns every third request into a fresh TCP
// connection and a fresh TLS handshake if there is one, and the cost lands in the latency figure as
// though the bank were slow. It is the most common way a load driver measures itself.
const idleConnectionsPerHost = 512

func newTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.MaxIdleConns = idleConnectionsPerHost * 2
	transport.MaxIdleConnsPerHost = idleConnectionsPerHost
	transport.MaxConnsPerHost = 0 // unbounded, because the model is open and a cap here would close it
	return transport
}

// Result is what one logical request produced.
type Result struct {
	Outcome  Outcome
	Status   int
	Attempts int
	// Key is the idempotency key every attempt carried, empty for a read.
	Key string
	// Latency is measured from the **intended** send time, not from when the request actually went
	// out. A driver that starts its stopwatch when it manages to send has already subtracted its
	// own queueing from the number, which is coordinated omission at the last possible moment.
	Latency time.Duration
	// RetryAfter is what a refusal asked for. Recorded rather than obeyed: an open model does not
	// re-offer a refused request, it goes on to the next scheduled one.
	RetryAfter time.Duration
	// Transfer and Hold are what the ledger allocated, when it allocated something.
	Transfer Transfer
	Hold     Hold
	// Err is the transport failure behind an unknown outcome, for the log and never for a metric
	// label.
	Err error
}

// retryPause is how long the driver waits before retrying an unknown outcome. Short, because the
// question is whether the request survived rather than whether the estate has recovered, and long
// enough not to arrive while the first attempt is still being processed.
const retryPause = 50 * time.Millisecond

// Send performs one logical request and returns what became of it.
//
// intended is the schedule's send time. Everything about this method's accounting hangs off it.
func (s *Sender) Send(ctx context.Context, request Request, intended time.Time) Result {
	result := Result{Outcome: Unknown}
	if request.MovesMoney {
		result.Key = request.Key
	}

	for attempt := 1; attempt <= s.settings.Attempts; attempt++ {
		result.Attempts = attempt
		outcome, status, body, retryAfter, err := s.attempt(ctx, request)
		result.Outcome, result.Status, result.RetryAfter, result.Err = outcome, status, retryAfter, err

		if outcome != Unknown {
			result.Transfer, result.Hold = learn(request, body)
			break
		}
		// The same key on every attempt. Minting a fresh one here is the single line that turns
		// this driver into one that double-spends under packet loss - and it would report success
		// while doing it, because both requests would answer 201.
		if attempt < s.settings.Attempts {
			timer := time.NewTimer(retryPause)
			select {
			case <-ctx.Done():
				timer.Stop()
				result.Err = ctx.Err()
				return finish(result, s.settings.Now(), intended)
			case <-timer.C:
			}
		}
	}

	return finish(result, s.settings.Now(), intended)
}

// finish stamps the latency. Measured from the intended time in one place, so that no future path
// out of Send can quietly measure from somewhere else.
func finish(result Result, now, intended time.Time) Result {
	result.Latency = now.Sub(intended)
	return result
}

// attempt performs one HTTP call.
func (s *Sender) attempt(ctx context.Context, request Request) (Outcome, int, []byte, time.Duration, error) {
	var body io.Reader
	if request.Body != nil {
		body = bytes.NewReader(request.Body)
	}
	outgoing, err := http.NewRequestWithContext(ctx, request.Method, s.settings.Origin+request.Path, body)
	if err != nil {
		return Unknown, 0, nil, 0, err
	}

	token, err := s.settings.Wallet.For(request.Subject, s.settings.Now())
	if err != nil {
		return Unknown, 0, nil, 0, err
	}
	outgoing.Header.Set("Authorization", "Bearer "+token)
	outgoing.Header.Set("Accept", "application/json")
	if request.Body != nil {
		outgoing.Header.Set("Content-Type", "application/json")
	}
	if request.MovesMoney {
		outgoing.Header.Set("Idempotency-Key", request.Key)
	}

	response, err := s.http.Do(outgoing)
	if err != nil {
		// A request that never got an answer is unknown, not failed. It may have been applied.
		return Unknown, 0, nil, 0, err
	}
	defer response.Body.Close()

	// Read the body even when it is not needed: an unread body cannot be reused, and a connection
	// dropped per request is the pool exhaustion described above by another route.
	payload, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes))
	if err != nil {
		return Unknown, response.StatusCode, nil, 0, err
	}
	return classify(response.StatusCode, request.MovesMoney), response.StatusCode, payload, retryAfter(response), nil
}

// maxResponseBytes bounds what the driver will read back. A statement page is the largest thing the
// contract returns and it is bounded at 500 movements.
const maxResponseBytes = 1 << 20

// classify maps a status to an outcome. The line between rejected and unknown is 4xx against 5xx,
// and it is the line WP-14 found live. The line between posted and replayed is 201 against 200, and
// it exists only for an operation that moves money: the ledger's own filter draws it in the same
// place, for the same reason.
func classify(status int, movesMoney bool) Outcome {
	switch {
	case status == http.StatusTooManyRequests:
		return Refused
	case status == http.StatusOK && movesMoney:
		return Replayed
	case status >= 200 && status < 300:
		return Posted
	case status >= 500:
		return Unknown
	default:
		return Rejected
	}
}

// retryAfter reads the header a refusal carries. RFC 9110 permits a delta in seconds, which is what
// edge/api-gateway sends, rounded up so that a Retry-After of 0 cannot invite an immediate retry.
func retryAfter(response *http.Response) time.Duration {
	raw := response.Header.Get("Retry-After")
	if raw == "" {
		return 0
	}
	seconds, err := strconv.Atoi(raw)
	if err != nil || seconds < 0 {
		return 0
	}
	return time.Duration(seconds) * time.Second
}

// learn reads back the references the ledger allocated, so that a later read reads something real.
func learn(request Request, body []byte) (Transfer, Hold) {
	if len(body) == 0 {
		return Transfer{}, Hold{}
	}
	var payload struct {
		TransferRef string `json:"transferRef"`
		HoldRef     string `json:"holdRef"`
		AccountRef  string `json:"accountRef"`
		Amount      struct {
			AmountMinor int64  `json:"amountMinor"`
			Currency    string `json:"currency"`
		} `json:"amount"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		// A body this driver cannot read is not an error: the request itself was answered, and
		// what it means is decided by the status. It simply teaches the run nothing.
		return Transfer{}, Hold{}
	}

	var transfer Transfer
	var hold Hold
	if payload.TransferRef != "" {
		transfer.Ref = payload.TransferRef
	}
	if payload.HoldRef != "" && request.Operation == "placeHold" {
		hold = Hold{
			Ref:        payload.HoldRef,
			AccountRef: payload.AccountRef,
			Amount:     money.Amount{Minor: payload.Amount.AmountMinor, Currency: money.Currency(payload.Amount.Currency)},
		}
	}
	return transfer, hold
}

// Key mints the idempotency key for one scheduled event.
//
// Deterministic - the business date, the event's ordinal in the schedule and the operation - which
// is what makes a run reproducible down to the bytes on the wire, and what makes a lost response
// recoverable: every attempt of one logical request computes the same key without having to carry
// it. The contract requires 16 to 64 characters and this is about forty.
func Key(date bankday.Date, seq int64, operation string) string {
	return fmt.Sprintf("wl-%s-%010d-%s", compact(date), seq, operation)
}

func compact(date bankday.Date) string {
	iso := date.String()
	return iso[0:4] + iso[5:7] + iso[8:10]
}
