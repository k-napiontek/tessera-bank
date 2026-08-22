package client_test

import (
	"encoding/json"
	"os"
	"regexp"
	"strings"
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
)

const ledgerContract = "../../../contracts/openapi/ledger-core.yaml"

// operation is what the contract declares about one operation, read at test time.
//
// Read rather than transcribed, for the reason F-64 records: a table copied into a test agrees with
// the test for ever and stops agreeing with the estate the day the contract moves. WP-20 read the
// five reference formats out of this same document; this reads the routes and the header the ledger
// requires on each of them.
type operation struct {
	Method   string
	Template string
	NeedsKey bool
}

func operationsInContract(t *testing.T) map[string]operation {
	t.Helper()
	source, err := os.ReadFile(ledgerContract)
	if err != nil {
		t.Fatalf("reading %s: %v", ledgerContract, err)
	}

	template := regexp.MustCompile(`^  (/\S*):$`)
	method := regexp.MustCompile(`^    (get|post|put|patch|delete):$`)

	found := map[string]operation{}
	var current operation
	var id string
	commit := func() {
		if id != "" {
			found[id] = current
		}
		id = ""
	}

	inPaths := false
	for _, line := range strings.Split(string(source), "\n") {
		switch {
		case line == "paths:":
			inPaths = true
		case inPaths && line != "" && !strings.HasPrefix(line, " "):
			commit()
			inPaths = false
		}
		if !inPaths {
			continue
		}

		if match := template.FindStringSubmatch(line); match != nil {
			commit()
			current = operation{Template: match[1]}
			continue
		}
		if match := method.FindStringSubmatch(line); match != nil {
			commit()
			current = operation{Template: current.Template, Method: strings.ToUpper(match[1])}
			continue
		}
		trimmed := strings.TrimSpace(line)
		if after, ok := strings.CutPrefix(trimmed, "operationId: "); ok {
			id = strings.TrimSpace(after)
		}
		if strings.Contains(trimmed, "parameters/IdempotencyKey") {
			current.NeedsKey = true
		}
	}
	commit()

	if len(found) == 0 {
		t.Fatalf("%s declares no operations - the reader is looking at the wrong document", ledgerContract)
	}
	return found
}

// known answers with whatever a test wants the run to have created already.
type known struct {
	transfer client.Transfer
	hold     client.Hold
	has      bool
}

func (k known) Transfer() (client.Transfer, bool)     { return k.transfer, k.has }
func (k known) TakeTransfer() (client.Transfer, bool) { return k.transfer, k.has }
func (k known) Hold() (client.Hold, bool)             { return k.hold, k.has }

func populated() known {
	return known{
		transfer: client.Transfer{Ref: "TB202608310000000007"},
		hold: client.Hold{
			Ref:        "HL202608310000000003",
			AccountRef: "TB0000000000000A",
			Amount:     money.Amount{Minor: 25_000, Currency: "PLN"},
		},
		has: true,
	}
}

func date(t *testing.T) bankday.Date {
	t.Helper()
	parsed, err := bankday.ParseDate("2026-08-31")
	if err != nil {
		t.Fatalf("ParseDate: %v", err)
	}
	return parsed
}

func action(operation string) population.Action {
	drawn := population.Action{
		Cohort:          "retail",
		CustomerRef:     "CU0000000001",
		AccountRef:      "TB0000000000000A",
		CounterpartyRef: "TB0000000000000B",
		Operation:       operation,
		TransferRef:     "TB202608310000000042",
		HoldRef:         "HL202608310000000042",
	}
	switch operation {
	case "createTransfer", "reverseTransfer", "placeHold", "captureHold":
		drawn.Amount = money.Amount{Minor: 12_345, Currency: "PLN"}
	}
	return drawn
}

// everyDrawnOperation is the operation mix of the committed model, which is what a run sends.
var everyDrawnOperation = []string{
	"getAccount", "getBalance", "getStatement", "listHolds",
	"createTransfer", "getTransfer", "reverseTransfer",
	"placeHold", "captureHold", "releaseHold",
}

func TestEveryRequestIsAnOperationTheContractDeclares(t *testing.T) {
	// A route the driver builds that the contract does not declare is a request the gateway refuses
	// with no-route, and the run would report it as the estate rejecting traffic. The gateway holds
	// itself to the same check in the other direction.
	declared := operationsInContract(t)

	for _, name := range everyDrawnOperation {
		built, err := client.Build(action(name), date(t), 7, "PLN", populated())
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		expected, found := declared[name]
		if !found {
			t.Errorf("%s is sent and the contract declares no such operation", name)
			continue
		}
		if built.Method != expected.Method || built.Template != expected.Template {
			t.Errorf("%s is sent as %s %s and declared as %s %s",
				name, built.Method, built.Template, expected.Method, expected.Template)
		}
	}
}

func TestTheKeyIsCarriedByExactlyTheOperationsThatRequireIt(t *testing.T) {
	// The ledger requires Idempotency-Key on the five money-moving operations and rejects a request
	// without one. A driver that decided this for itself would either double-spend under packet
	// loss or collect 400s that read like a malformed body.
	declared := operationsInContract(t)

	for _, name := range everyDrawnOperation {
		built, err := client.Build(action(name), date(t), 7, "PLN", populated())
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if built.MovesMoney != declared[name].NeedsKey {
			t.Errorf("%s carries a key: %v; the contract requires one: %v",
				name, built.MovesMoney, declared[name].NeedsKey)
		}
	}
}

func TestAReadOfSomethingNotYetCreatedIsNotSentAtAll(t *testing.T) {
	// Sending the drawn reference instead would be one line and would fill the rejected column with
	// 404s on transfers nobody ever made - the driver measuring itself, and the exact failure the
	// package objective warns about.
	empty := known{}
	for _, name := range []string{"getTransfer", "reverseTransfer", "captureHold", "releaseHold"} {
		if _, err := client.Build(action(name), date(t), 7, "PLN", empty); err != client.ErrNoReferenceYet {
			t.Errorf("%s with nothing posted yet returned %v", name, err)
		}
	}
}

func TestADependentOperationNamesWhatTheRunPosted(t *testing.T) {
	// Not what the population drew. The ledger allocates its own transfer and hold references; the
	// drawn ones exist for WP-25, where the older strata are told the reference instead.
	drawn := action("getTransfer")
	built, err := client.Build(drawn, date(t), 7, "PLN", populated())
	if err != nil {
		t.Fatalf("getTransfer: %v", err)
	}
	if strings.Contains(built.Path, drawn.TransferRef) {
		t.Errorf("the path names the drawn reference %s, which the ledger never issued", drawn.TransferRef)
	}
	if !strings.Contains(built.Path, populated().transfer.Ref) {
		t.Errorf("the path is %s and names no transfer this run posted", built.Path)
	}
}

func TestACaptureNeverExceedsWhatWasHeld(t *testing.T) {
	// The drawn amount has nothing to do with what the hold reserved. A capture above it is refused
	// by the ledger - correctly - and would be a rejection the driver invented.
	drawn := action("captureHold")
	drawn.Amount = money.Amount{Minor: 9_999_999, Currency: "PLN"}

	built, err := client.Build(drawn, date(t), 7, "PLN", populated())
	if err != nil {
		t.Fatalf("captureHold: %v", err)
	}
	var body struct {
		Amount struct {
			AmountMinor int64 `json:"amountMinor"`
		} `json:"amount"`
		CreditAccountRef string `json:"creditAccountRef"`
	}
	if err := json.Unmarshal(built.Body, &body); err != nil {
		t.Fatalf("the capture body is not JSON: %v", err)
	}
	if body.Amount.AmountMinor != populated().hold.Amount.Minor {
		t.Errorf("captures %d, and the hold reserved %d",
			body.Amount.AmountMinor, populated().hold.Amount.Minor)
	}
}

func TestATransferGoesInTheCurrencyTheAccountsHold(t *testing.T) {
	// The model draws a currency per transfer from a mix of up to five; the ledger fixes an
	// account's currency when it is opened and requires both sides to match. Sending the drawn
	// currency would collect a 422 the estate is right to return, so the substitution is made and
	// counted rather than hidden.
	drawn := action("createTransfer")
	drawn.Amount = money.Amount{Minor: 4_500, Currency: "EUR"}

	built, err := client.Build(drawn, date(t), 7, "PLN", populated())
	if err != nil {
		t.Fatalf("createTransfer: %v", err)
	}
	if !built.CurrencySubstituted {
		t.Error("a EUR transfer against PLN accounts was not recorded as substituted")
	}
	if !strings.Contains(string(built.Body), `"currency":"PLN"`) {
		t.Errorf("the body is %s", built.Body)
	}

	matching := action("createTransfer")
	matching.Amount = money.Amount{Minor: 4_500, Currency: "PLN"}
	built, err = client.Build(matching, date(t), 7, "PLN", populated())
	if err != nil {
		t.Fatalf("createTransfer: %v", err)
	}
	if built.CurrencySubstituted {
		t.Error("a PLN transfer against PLN accounts was counted as a substitution")
	}
}

// **F-103.** A driven day was dated by the machine's clock rather than by the day it was driving.
//
// The ledger defaults valueDate to LocalDate.now when the request omits one - Transfer.java:97 - and
// this driver omitted it, while already holding the business date it mints references and
// idempotency keys from. So a run of 2026-03-02 wrote journal entries dated today, and a
// reconciliation that asks the ledger for the business date's postings got none of them. It was
// invisible until WP-25c compared the ledger against a stratum-0 master for the first time.
//
// The field is in contracts/openapi/ledger-core.yaml already, optional, described as "defaults to
// the current business date when omitted". Nothing had to change but the sending of it.
func TestATransferIsDatedByTheBusinessDayItBelongsTo(t *testing.T) {
	built, err := client.Build(action("createTransfer"), date(t), 7, "PLN", populated())
	if err != nil {
		t.Fatalf("createTransfer: %v", err)
	}
	if !strings.Contains(string(built.Body), `"valueDate":"`+date(t).String()+`"`) {
		t.Errorf("the transfer carries no value date for %s: %s", date(t), built.Body)
	}
}

// Seeding is dated the day *before* the run, which is the rule services/ledger-loader already
// follows in Header.openingDate: an opening balance is the position the day starts from, not part of
// it. batch/recon depends on exactly that - a posting counts towards what the master ought to hold
// when its reference is in the movement file, or its value date is earlier than the business date.
// Funding is in neither the movement file nor the day, so dated on the day it would be excluded from
// the expected balance and every account would drift by its opening balance.
func TestFundingIsDatedTheDayBeforeTheRun(t *testing.T) {
	funded, err := client.Fund("TB0000000000000T", "TB0000000000000A", "CU0000000001",
		money.Amount{Minor: 1_000_000, Currency: "PLN"}, date(t))
	if err != nil {
		t.Fatalf("Fund: %v", err)
	}
	before := date(t).AddDays(-1).String()
	if !strings.Contains(string(funded.Body), `"valueDate":"`+before+`"`) {
		t.Errorf("funding for %s is not dated %s: %s", date(t), before, funded.Body)
	}
}

func TestNoRequestCarriesAnythingResemblingPersonalData(t *testing.T) {
	// data-classification.md asks that this be checked against the bytes rather than asserted about
	// intent. The reference field is 35 characters of free text and is exactly where a real system
	// leaks a payer's name into a fixture.
	denied := regexp.MustCompile(`(?i)name|email|address|phone|pesel|iban|holder|birth|street|surname|passport|card`)

	for _, name := range everyDrawnOperation {
		built, err := client.Build(action(name), date(t), 7, "PLN", populated())
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if match := denied.FindString(string(built.Body)); match != "" {
			t.Errorf("%s sends a body naming %q: %s", name, match, built.Body)
		}
		if match := denied.FindString(built.Path); match != "" {
			t.Errorf("%s sends a path naming %q: %s", name, match, built.Path)
		}
	}
}

func TestTheTemplateIsBoundedAndThePathIsNot(t *testing.T) {
	// The template is the metrics label. A path carries an account reference, and using it as a
	// label would give every account in a 1.2 million customer population its own series - which is
	// the cardinality trap edge/api-gateway keeps a route class to avoid.
	for _, name := range everyDrawnOperation {
		built, err := client.Build(action(name), date(t), 7, "PLN", populated())
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if strings.Contains(built.Path, "{") {
			t.Errorf("%s sends an unsubstituted template: %s", name, built.Path)
		}
		if !strings.HasPrefix(built.Path, "/") {
			t.Errorf("%s builds a path that is not absolute: %s", name, built.Path)
		}
	}
}

func TestAStatementAsksForARangeRatherThanADay(t *testing.T) {
	// from and to are both required, and a range of one day is answered from almost nothing. A
	// month is what a customer looks at and it is wide enough to make the ledger page, which is the
	// query F-24 is about.
	built, err := client.Build(action("getStatement"), date(t), 7, "PLN", populated())
	if err != nil {
		t.Fatalf("getStatement: %v", err)
	}
	if !strings.Contains(built.Path, "from=2026-08-01") || !strings.Contains(built.Path, "to=2026-08-31") {
		t.Errorf("the statement range is %s", built.Path)
	}
}

func TestAnOperationWithNoRequestShapeIsRefusedRatherThanSkipped(t *testing.T) {
	// Unreachable from the committed model, which is validated against the contract's eleven
	// operationIds. Reachable the day somebody writes a second model, and silently sending nothing
	// would be far worse than stopping.
	if _, err := client.Build(action("auditTheBank"), date(t), 7, "PLN", populated()); err == nil {
		t.Error("built a request for an operation the ledger does not serve")
	}
}

func TestSeedingSendsTheContractsOpenAccountAndFundsFromTheTreasury(t *testing.T) {
	declared := operationsInContract(t)

	opened, err := client.OpenAccount("CU0000000001", "TB0000000000000A", "LIABILITY", "PLN")
	if err != nil {
		t.Fatalf("OpenAccount: %v", err)
	}
	if opened.Method != declared["openAccount"].Method || opened.Template != declared["openAccount"].Template {
		t.Errorf("openAccount is sent as %s %s", opened.Method, opened.Template)
	}
	// openAccount is the one money-shaped operation the contract does not require a key on, because
	// the reference is supplied by the caller and a repeat is a 409 rather than a second account.
	if opened.MovesMoney != declared["openAccount"].NeedsKey {
		t.Errorf("openAccount carries a key: %v", opened.MovesMoney)
	}

	funded, err := client.Fund("TB0000000000000T", "TB0000000000000A", "CU0000000001",
		money.Amount{Minor: 1_000_000, Currency: "PLN"}, date(t))
	if err != nil {
		t.Fatalf("Fund: %v", err)
	}
	if !funded.MovesMoney {
		t.Error("funding moves money and must carry an idempotency key")
	}
	if !strings.Contains(string(funded.Body), `"debitAccountRef":"TB0000000000000T"`) {
		t.Errorf("funding does not debit the treasury: %s", funded.Body)
	}
}
