// Package client sends a drawn action to edge/api-gateway and says what became of it.
//
// It behaves like a customer application rather than like a load tool, and that distinction decides
// almost every choice here. A load tool sends requests and counts the ones that came back 200. A
// customer application holds an idempotency key across a retry, treats a lost response as an unknown
// outcome rather than a failure, backs off when it is told to, and reads back the thing it just
// created rather than a reference it invented. WP-14 learned the first three live, and a driver that
// ignores them generates traffic no client in this estate would ever produce - which makes every
// number it collects a measurement of the wrong system.
package client

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
)

// Request is one HTTP call, built and ready to send.
type Request struct {
	// Operation is the contract's operationId. It is the label every count is kept under.
	Operation string
	Method    string
	// Template is the OpenAPI path template, braces and all. It is bounded, unlike Path, which
	// carries an account reference and would give every account in the population its own metric
	// series - the reason edge/api-gateway keeps a route class beside its path.
	Template string
	// Path is what is actually sent, query string included.
	Path string
	// Subject is the customer the token is minted for. Never a name, always the pseudonymous
	// reference the model draws.
	Subject string
	// Body is the JSON payload, or nil for a read.
	Body []byte
	// Key is the Idempotency-Key the request carries, empty for a read. Minted once per scheduled
	// event and reused by every retry of it - see Key.
	Key string
	// MovesMoney reports whether the ledger requires an Idempotency-Key. The five operations that
	// do are the five the contract marks, and a request that omits the header is refused with a 400
	// that reads like a malformed body.
	MovesMoney bool
	// CurrencySubstituted records that the drawn currency was not the one the accounts involved are
	// open in, so the request went in the currency they hold. Counted rather than hidden: see
	// seeding.BaseCurrency for why a single-currency estate cannot host a per-transfer mix.
	CurrencySubstituted bool
}

// Transfer is a transfer the run has posted, remembered so that a later read reads something real.
type Transfer struct {
	Ref string
}

// Hold is a hold the run has placed, with what it reserved. The amount is remembered because a
// capture may not exceed it, and a capture the ledger refuses on arithmetic is a rejection the
// driver manufactured rather than one the estate produced.
type Hold struct {
	Ref        string
	AccountRef string
	Amount     money.Amount
}

// References supplies what the run itself has created.
//
// The population draws a fresh transfer and hold reference for every event, which is what WP-25
// needs - the older strata are told the reference rather than allocating it. The ledger allocates
// its own, so a driver that sent the drawn one would spend the run collecting 404s on transfers
// nobody made. Reading back what this run posted is both the honest traffic and the only traffic
// that exercises the read path at all.
type References interface {
	// Transfer returns a transfer this run has posted, if it has posted one. A read does not
	// consume it: a transfer can be fetched any number of times.
	Transfer() (Transfer, bool)
	// TakeTransfer returns a transfer and gives it up. A reversal consumes what it reverses -
	// reversing the same transfer twice is a conflict the ledger is right to refuse, and one this
	// driver would have manufactured.
	TakeTransfer() (Transfer, bool)
	// Hold returns a hold this run has placed and gives it up, for the same reason: a hold can be
	// captured or released once.
	Hold() (Hold, bool)
}

// ErrNoReferenceYet reports an operation that reads or completes something this run has not created.
//
// It is not a failure and it is never a rejected request: it means the schedule asked for "read the
// transfer I made" before any transfer had been made. The alternative - sending a reference the run
// invented - would manufacture 404s that no customer application would ever produce, and every one
// of them would land in the rejected column as though the ledger had refused something real.
var ErrNoReferenceYet = errors.New("client: the run has not created anything this operation can name")

// statementDays is how far back a statement request reaches. A month is what a customer looks at,
// and it is wide enough that the ledger's keyset seek has to page rather than answer from one row -
// which is the query F-24 is about.
const statementDays = 30

// Build turns one drawn action into the request a customer application would send.
//
// held is the currency the estate's accounts are open in. The ledger fixes an account's currency
// when it is opened and requires a transfer to be in the currency of both accounts, while the model
// draws a currency per transfer from a mix of up to five. The request goes in the currency the
// accounts hold and says so, rather than being sent in the drawn currency to collect a 422 the
// estate is right to return.
func Build(action population.Action, date bankday.Date, seq int64, held money.Currency, known References) (Request, error) {
	request := Request{
		Operation: action.Operation,
		Subject:   action.CustomerRef,
	}

	amount := action.Amount
	if action.MovesMoney() && amount.Currency != held {
		amount.Currency = held
		request.CurrencySubstituted = true
	}

	switch action.Operation {
	case "getAccount":
		request.Method, request.Template = "GET", "/accounts/{accountRef}"
		request.Path = "/accounts/" + action.AccountRef

	case "getBalance":
		request.Method, request.Template = "GET", "/accounts/{accountRef}/balance"
		request.Path = "/accounts/" + action.AccountRef + "/balance"

	case "getStatement":
		request.Method, request.Template = "GET", "/accounts/{accountRef}/statement"
		from, err := shift(date, -statementDays)
		if err != nil {
			return Request{}, err
		}
		query := url.Values{"from": {from}, "to": {date.String()}}
		request.Path = "/accounts/" + action.AccountRef + "/statement?" + query.Encode()

	case "listHolds":
		request.Method, request.Template = "GET", "/accounts/{accountRef}/holds"
		request.Path = "/accounts/" + action.AccountRef + "/holds"

	case "createTransfer":
		request.Method, request.Template = "POST", "/transfers"
		request.Path = "/transfers"
		request.MovesMoney = true
		body, err := json.Marshal(transferRequest{
			DebitAccountRef:  action.AccountRef,
			CreditAccountRef: action.CounterpartyRef,
			Amount:           minor(amount),
			Reference:        remittance(action.Cohort),
			ValueDate:        date.String(),
		})
		if err != nil {
			return Request{}, err
		}
		request.Body = body

	case "placeHold":
		request.Method, request.Template = "POST", "/accounts/{accountRef}/holds"
		request.Path = "/accounts/" + action.AccountRef + "/holds"
		request.MovesMoney = true
		body, err := json.Marshal(holdRequest{
			Amount:    minor(amount),
			Reference: remittance(action.Cohort),
		})
		if err != nil {
			return Request{}, err
		}
		request.Body = body

	case "getTransfer":
		posted, found := known.Transfer()
		if !found {
			return Request{}, ErrNoReferenceYet
		}
		request.Method, request.Template = "GET", "/transfers/{transferRef}"
		request.Path = "/transfers/" + posted.Ref

	case "reverseTransfer":
		posted, found := known.TakeTransfer()
		if !found {
			return Request{}, ErrNoReferenceYet
		}
		request.Method, request.Template = "POST", "/transfers/{transferRef}/reversals"
		request.Path = "/transfers/" + posted.Ref + "/reversals"
		request.MovesMoney = true
		body, err := json.Marshal(reversalRequest{
			Reason:    "workload run reversal",
			Reference: remittance(action.Cohort),
		})
		if err != nil {
			return Request{}, err
		}
		request.Body = body

	case "captureHold":
		placed, found := known.Hold()
		if !found {
			return Request{}, ErrNoReferenceYet
		}
		request.Method, request.Template = "POST", "/holds/{holdRef}/capture"
		request.Path = "/holds/" + placed.Ref + "/capture"
		request.MovesMoney = true
		// The held amount, not the drawn one. A capture above what was reserved is refused by the
		// ledger, correctly, and it would be a rejection this driver invented.
		body, err := json.Marshal(captureRequest{
			CreditAccountRef: action.CounterpartyRef,
			Amount:           minor(placed.Amount),
			Reference:        remittance(action.Cohort),
		})
		if err != nil {
			return Request{}, err
		}
		request.Body = body

	case "releaseHold":
		placed, found := known.Hold()
		if !found {
			return Request{}, ErrNoReferenceYet
		}
		request.Method, request.Template = "POST", "/holds/{holdRef}/release"
		request.Path = "/holds/" + placed.Ref + "/release"
		request.MovesMoney = true

	default:
		// The model is validated against the eleven operationIds the contract declares, so this is
		// unreachable from the committed model - and it is here because the day it is reachable,
		// silently sending nothing would be far worse than stopping.
		return Request{}, fmt.Errorf("client: no request shape for %q", action.Operation)
	}

	if action.MovesMoney() && request.Body == nil && request.Operation != "releaseHold" {
		return Request{}, fmt.Errorf("client: %s carries an amount and sends no body", action.Operation)
	}
	if request.MovesMoney {
		request.Key = Key(date, seq, action.Operation)
	}
	return request, nil
}

// OpenAccount is the request seeding sends before a run starts. It is the one operation the
// population never draws: a customer does not open an account fifteen times a day.
func OpenAccount(customerRef, accountRef string, accountType string, currency money.Currency) (Request, error) {
	body, err := json.Marshal(openAccountRequest{
		AccountRef:  accountRef,
		CustomerRef: customerRef,
		AccountType: accountType,
		Currency:    string(currency),
	})
	if err != nil {
		return Request{}, err
	}
	return Request{
		Operation: "openAccount",
		Method:    "POST",
		Template:  "/accounts",
		Path:      "/accounts",
		Subject:   customerRef,
		Body:      body,
	}, nil
}

// Fund is the opening credit seeding sends, from the treasury account to a customer's.
//
// The key is derived from the account being funded, so that a second seeding pass over an estate
// that is already seeded replays rather than funding it twice.
//
// Dated the day *before* the run, which is the rule services/ledger-loader already follows in
// Header.openingDate: an opening balance is the position the day starts from rather than part of it.
// batch/recon counts a posting towards what the master ought to hold when its reference is in the
// movement file or its value date is earlier than the business date, and funding is in neither the
// movement file nor the day - so dated on the day it would be left out and every account would drift
// by exactly its opening balance. F-103.
func Fund(treasuryRef, accountRef, subject string, amount money.Amount, date bankday.Date) (Request, error) {
	body, err := json.Marshal(transferRequest{
		DebitAccountRef:  treasuryRef,
		CreditAccountRef: accountRef,
		Amount:           minor(amount),
		Reference:        "opening balance",
		ValueDate:        date.AddDays(-1).String(),
	})
	if err != nil {
		return Request{}, err
	}
	return Request{
		Operation:  "createTransfer",
		Method:     "POST",
		Template:   "/transfers",
		Path:       "/transfers",
		Subject:    subject,
		Body:       body,
		MovesMoney: true,
		Key:        "wl-funding-" + accountRef,
	}, nil
}

// The request bodies, exactly as contracts/openapi/ledger-core.yaml declares them. Every schema
// there is additionalProperties: false, so a field this driver invents is a 400 rather than a field
// the ledger ignores.

type openAccountRequest struct {
	AccountRef  string `json:"accountRef"`
	CustomerRef string `json:"customerRef"`
	AccountType string `json:"accountType"`
	Currency    string `json:"currency"`
}

type transferRequest struct {
	DebitAccountRef  string     `json:"debitAccountRef"`
	CreditAccountRef string     `json:"creditAccountRef"`
	Amount           amountJSON `json:"amount"`
	Reference        string     `json:"reference,omitempty"`
	// ValueDate is the business day the movement belongs to. Optional in the contract and defaulted
	// by the ledger to LocalDate.now, which is why a driven day used to be dated by the clock of the
	// machine that drove it rather than by the day it was driving. F-103.
	ValueDate string `json:"valueDate,omitempty"`
}

type holdRequest struct {
	Amount    amountJSON `json:"amount"`
	Reference string     `json:"reference,omitempty"`
}

type captureRequest struct {
	CreditAccountRef string     `json:"creditAccountRef"`
	Amount           amountJSON `json:"amount"`
	Reference        string     `json:"reference,omitempty"`
}

type reversalRequest struct {
	Reason    string `json:"reason"`
	Reference string `json:"reference,omitempty"`
}

// amountJSON is Money: minor units and a currency, never a decimal and never a float.
type amountJSON struct {
	AmountMinor int64  `json:"amountMinor"`
	Currency    string `json:"currency"`
}

func minor(amount money.Amount) amountJSON {
	return amountJSON{AmountMinor: amount.Minor, Currency: string(amount.Currency)}
}

// remittance is the free-text reference. It names the cohort and nothing else: the field is 35
// characters of SEPA remittance information, and it is exactly where a real system leaks a payer's
// name into a load fixture.
func remittance(cohort string) string { return "workload " + cohort }

// shift moves a business date by whole days, for the statement range.
func shift(date bankday.Date, days int) (string, error) {
	parsed, err := time.Parse("2006-01-02", date.String())
	if err != nil {
		return "", fmt.Errorf("client: unreadable business date %s: %w", date, err)
	}
	return parsed.AddDate(0, 0, days).Format("2006-01-02"), nil
}
