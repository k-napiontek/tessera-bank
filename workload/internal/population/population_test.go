package population_test

import (
	"math"
	"os"
	"regexp"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
)

const ledgerContract = "../../../contracts/openapi/ledger-core.yaml"

// patternFor reads a reference format out of the ledger's OpenAPI document.
//
// Read rather than copied, deliberately. A pattern pasted into a test agrees with the test for ever
// and stops agreeing with the estate the day the contract moves - which is precisely the drift the
// contracts directory exists to prevent, and F-64 records happening in a document that copied
// requirement ids instead of reading them.
func patternFor(t *testing.T, key string) *regexp.Regexp {
	t.Helper()
	source, err := os.ReadFile(ledgerContract)
	if err != nil {
		t.Fatalf("reading %s: %v", ledgerContract, err)
	}
	lines := strings.Split(string(source), "\n")
	for i, line := range lines {
		if strings.TrimSpace(line) != key+":" {
			continue
		}
		for _, following := range lines[i+1:] {
			trimmed := strings.TrimSpace(following)
			if after, found := strings.CutPrefix(trimmed, "pattern: "); found {
				expr := strings.Trim(after, "'\"")
				compiled, err := regexp.Compile(expr)
				if err != nil {
					t.Fatalf("%s declares an uncompilable pattern %q: %v", key, expr, err)
				}
				return compiled
			}
			if trimmed == "" || strings.HasSuffix(trimmed, ":") && !strings.Contains(trimmed, " ") {
				break // the next schema started and this one declared no pattern
			}
		}
	}
	t.Fatalf("%s declares no pattern in %s", key, ledgerContract)
	return nil
}

// operationIDs reads every operation the ledger actually serves.
func operationIDs(t *testing.T) map[string]bool {
	t.Helper()
	source, err := os.ReadFile(ledgerContract)
	if err != nil {
		t.Fatalf("reading %s: %v", ledgerContract, err)
	}
	found := map[string]bool{}
	for _, line := range strings.Split(string(source), "\n") {
		if after, ok := strings.CutPrefix(strings.TrimSpace(line), "operationId: "); ok {
			found[strings.TrimSpace(after)] = true
		}
	}
	if len(found) == 0 {
		t.Fatalf("%s declares no operations at all", ledgerContract)
	}
	return found
}

func testSpec() population.Spec {
	return population.Spec{
		Size:                1_200_000,
		AccountsPerCustomer: 2,
		Cohorts: []population.Cohort{
			{
				ID: "retail", Share: 0.80, EventsPerCustomerPerDay: 15,
				Amount:     population.AmountSpec{MedianMinor: 12000, Sigma: 1.1, MinMinor: 100, MaxMinor: 5_000_000},
				Currencies: []population.Weighted[money.Currency]{{Value: "PLN", Weight: 0.96}, {Value: "EUR", Weight: 0.03}, {Value: "USD", Weight: 0.01}},
				Operations: []population.Weighted[string]{
					{Value: "getAccount", Weight: 0.06}, {Value: "getBalance", Weight: 0.44},
					{Value: "getStatement", Weight: 0.18}, {Value: "createTransfer", Weight: 0.22},
					{Value: "getTransfer", Weight: 0.06}, {Value: "listHolds", Weight: 0.02},
					{Value: "placeHold", Weight: 0.01}, {Value: "releaseHold", Weight: 0.01},
				},
			},
			{
				ID: "corporate", Share: 0.20, EventsPerCustomerPerDay: 36,
				Amount:     population.AmountSpec{MedianMinor: 1_250_000, Sigma: 1.4, MinMinor: 10000, MaxMinor: 500_000_000},
				Currencies: []population.Weighted[money.Currency]{{Value: "PLN", Weight: 0.72}, {Value: "EUR", Weight: 0.28}},
				Operations: []population.Weighted[string]{
					{Value: "createTransfer", Weight: 0.60}, {Value: "getStatement", Weight: 0.25},
					{Value: "captureHold", Weight: 0.10}, {Value: "reverseTransfer", Weight: 0.05},
				},
			},
		},
	}
}

func build(t *testing.T) population.Population {
	t.Helper()
	people, err := population.New(testSpec())
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return people
}

var businessDate = bankday.NewDate(2026, time.August, 31)

func TestEveryReferenceMatchesThePatternInTheContract(t *testing.T) {
	people := build(t)
	account := patternFor(t, "AccountRef")
	customer := patternFor(t, "CustomerRef")
	transfer := patternFor(t, "TransferRef")
	hold := patternFor(t, "HoldRef")
	movement := patternFor(t, "movementRef")

	for seq := int64(0); seq < 20000; seq++ {
		action := people.Draw(42, seq, businessDate)

		if !customer.MatchString(action.CustomerRef) {
			t.Fatalf("customerRef %q does not match %s", action.CustomerRef, customer)
		}
		if !account.MatchString(action.AccountRef) {
			t.Fatalf("accountRef %q does not match %s", action.AccountRef, account)
		}
		if action.CounterpartyRef != "" && !account.MatchString(action.CounterpartyRef) {
			t.Fatalf("counterparty %q does not match %s", action.CounterpartyRef, account)
		}
		if action.TransferRef != "" && !transfer.MatchString(action.TransferRef) {
			t.Fatalf("transferRef %q does not match %s", action.TransferRef, transfer)
		}
		if action.HoldRef != "" && !hold.MatchString(action.HoldRef) {
			t.Fatalf("holdRef %q does not match %s", action.HoldRef, hold)
		}
		for _, ref := range action.MovementRefs {
			if ref != "" && !movement.MatchString(ref) {
				t.Fatalf("movementRef %q does not match %s", ref, movement)
			}
		}
	}
}

func TestTheFourReferenceFormatsAreGenuinelyDifferent(t *testing.T) {
	// WP-20's Constraints call this out because it is the mistake a single "reference" helper makes:
	// one format applied to four things, three of which then fail validation at the far end.
	account := patternFor(t, "AccountRef")
	customer := patternFor(t, "CustomerRef")
	transfer := patternFor(t, "TransferRef")
	movement := patternFor(t, "movementRef")

	action := build(t).Draw(1, 0, businessDate)
	if account.MatchString(action.CustomerRef) {
		t.Error("a customer reference passes the account pattern")
	}
	if customer.MatchString(action.AccountRef) {
		t.Error("an account reference passes the customer pattern")
	}
	if transfer.MatchString(action.AccountRef) {
		t.Error("an account reference passes the transfer pattern")
	}
	if movement.MatchString(action.AccountRef) {
		t.Error("an account reference passes the movement pattern")
	}
}

func TestEveryOperationIsOneTheLedgerServes(t *testing.T) {
	served := operationIDs(t)
	people := build(t)
	drawn := map[string]int{}
	for seq := int64(0); seq < 20000; seq++ {
		operation := people.Draw(9, seq, businessDate).Operation
		if !served[operation] {
			t.Fatalf("drew %q, which is not an operationId in %s", operation, ledgerContract)
		}
		drawn[operation]++
	}
	if len(drawn) < 4 {
		t.Errorf("only %d distinct operations in 20000 draws: %v", len(drawn), drawn)
	}
}

func TestADrawIsReproducibleAndIndependentOfOrder(t *testing.T) {
	// Seeded per event rather than per run, so a driver may fan the schedule across workers and
	// still produce the day the manifest describes. A generator threaded through one mutable source
	// would make the schedule depend on which worker got there first.
	people := build(t)
	for _, seq := range []int64{0, 1, 999, 1_000_000} {
		first := people.Draw(42, seq, businessDate)
		second := people.Draw(42, seq, businessDate)
		if first != second {
			t.Fatalf("seq %d drew %+v then %+v", seq, first, second)
		}
	}
	if people.Draw(42, 5, businessDate) == people.Draw(43, 5, businessDate) {
		t.Error("seeds 42 and 43 drew the same action")
	}
}

func TestATransferNeverSendsMoneyToItself(t *testing.T) {
	// SAME_ACCOUNT is a fault customer-master raises (F-51). A load run that spent its day
	// collecting business refusals would measure the error path and call it throughput.
	people := build(t)
	for seq := int64(0); seq < 50000; seq++ {
		action := people.Draw(3, seq, businessDate)
		if action.CounterpartyRef != "" && action.CounterpartyRef == action.AccountRef {
			t.Fatalf("seq %d sends %s to itself", seq, action.AccountRef)
		}
	}
}

func TestAmountsStayInsideTheDeclaredRangeAndAreNeverZero(t *testing.T) {
	people := build(t)
	spec := testSpec()
	byCohort := map[string]population.AmountSpec{}
	for _, cohort := range spec.Cohorts {
		byCohort[cohort.ID] = cohort.Amount
	}
	seen := 0
	for seq := int64(0); seq < 50000; seq++ {
		action := people.Draw(11, seq, businessDate)
		if !action.MovesMoney() {
			continue
		}
		seen++
		bounds := byCohort[action.Cohort]
		if action.Amount.Minor < bounds.MinMinor || action.Amount.Minor > bounds.MaxMinor {
			t.Fatalf("%s drew %s, outside [%d, %d]", action.Cohort, action.Amount, bounds.MinMinor, bounds.MaxMinor)
		}
		if !action.Amount.IsPositive() {
			t.Fatalf("%s drew a non-positive amount %s", action.Cohort, action.Amount)
		}
		if !money.Valid(action.Amount.Currency) {
			t.Fatalf("drew currency %q, which this estate cannot carry", action.Amount.Currency)
		}
	}
	if seen == 0 {
		t.Fatal("no money moved in 50000 draws")
	}
}

func TestTheAmountDrawHasTheDeclaredMedian(t *testing.T) {
	// A clamp with a bad draw behind it produces every amount at one bound and passes the range
	// test above. The median is what proves there is a distribution here rather than a constant.
	people, err := population.New(population.Spec{
		Size: 1000, AccountsPerCustomer: 1,
		Cohorts: []population.Cohort{{
			ID: "retail", Share: 1.0, EventsPerCustomerPerDay: 1,
			Amount:     population.AmountSpec{MedianMinor: 12000, Sigma: 1.1, MinMinor: 1, MaxMinor: 1_000_000_000},
			Currencies: []population.Weighted[money.Currency]{{Value: "PLN", Weight: 1.0}},
			Operations: []population.Weighted[string]{{Value: "createTransfer", Weight: 1.0}},
		}},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	var amounts []int64
	for seq := int64(0); seq < 40000; seq++ {
		amounts = append(amounts, people.Draw(77, seq, businessDate).Amount.Minor)
	}
	sort.Slice(amounts, func(i, j int) bool { return amounts[i] < amounts[j] })
	median := amounts[len(amounts)/2]
	if relative := math.Abs(float64(median-12000)) / 12000; relative > 0.05 {
		t.Errorf("the median draw is %d, and the cohort declares 12000 (off by %.1f%%)", median, relative*100)
	}
	if amounts[0] == amounts[len(amounts)-1] {
		t.Error("every draw gave the same amount - there is no distribution here")
	}
}

func TestCohortsAreDrawnByEventsRatherThanByHeadcount(t *testing.T) {
	// The trap: a corporate customer is 20% of this fixture's population and generates 36 events a
	// day against retail's 15, so corporates are 37.5% of the *demand*. Drawing by headcount would
	// understate the heaviest, most expensive traffic in the estate by nearly half.
	people := build(t)
	counts := map[string]int{}
	const draws = 200000
	for seq := int64(0); seq < draws; seq++ {
		counts[people.Draw(5, seq, businessDate).Cohort]++
	}
	retailWeight := 0.80 * 15
	corporateWeight := 0.20 * 36
	want := corporateWeight / (retailWeight + corporateWeight)
	got := float64(counts["corporate"]) / draws
	if math.Abs(got-want) > 0.01 {
		t.Errorf("corporate is %.4f of the events, want %.4f", got, want)
	}
}

func TestCurrencyMixIsRealised(t *testing.T) {
	people := build(t)
	counts := map[money.Currency]int{}
	total := 0
	for seq := int64(0); seq < 200000; seq++ {
		action := people.Draw(6, seq, businessDate)
		if !action.MovesMoney() {
			continue
		}
		counts[action.Amount.Currency]++
		total++
	}
	// Retail is 0.96 PLN, corporate 0.72, weighted by each cohort's share of money-moving events.
	// Rather than reproduce that arithmetic here, assert the shape: PLN dominates, EUR is present
	// and material, USD is present and rare, and nothing outside the mix ever appears.
	for currency := range counts {
		if currency != "PLN" && currency != "EUR" && currency != "USD" {
			t.Errorf("drew %q, which is in no cohort's mix", currency)
		}
	}
	pln := float64(counts["PLN"]) / float64(total)
	if pln < 0.75 || pln > 0.95 {
		t.Errorf("PLN is %.3f of transfers, which is outside the mix the cohorts declare", pln)
	}
	if counts["EUR"] == 0 || counts["USD"] == 0 {
		t.Errorf("a currency in the mix was never drawn: %v", counts)
	}
}

func TestASpecThatDoesNotAddUpIsRefused(t *testing.T) {
	cases := map[string]func(*population.Spec){
		"shares that do not add to 1":      func(s *population.Spec) { s.Cohorts[0].Share = 0.5 },
		"a cohort with no operations":      func(s *population.Spec) { s.Cohorts[0].Operations = nil },
		"a cohort with no currencies":      func(s *population.Spec) { s.Cohorts[0].Currencies = nil },
		"a currency the estate refuses":    func(s *population.Spec) { s.Cohorts[0].Currencies[0].Value = "JPY" },
		"a share that is not whole people": func(s *population.Spec) { s.Size = 7 },
		"no population at all":             func(s *population.Spec) { s.Size = 0 },
		"no accounts":                      func(s *population.Spec) { s.AccountsPerCustomer = 0 },
		"a median outside its own range":   func(s *population.Spec) { s.Cohorts[0].Amount.MedianMinor = 1 },
		"a negative weight":                func(s *population.Spec) { s.Cohorts[0].Operations[0].Weight = -1 },
	}
	for name, breakIt := range cases {
		t.Run(name, func(t *testing.T) {
			spec := testSpec()
			breakIt(&spec)
			if _, err := population.New(spec); err == nil {
				t.Errorf("a spec with %s was accepted", name)
			}
		})
	}
}

func TestTheTreasuryIsAReferenceNoCustomerCanHold(t *testing.T) {
	// A run has to debit its funding from somewhere, and the ledger is double-entry: an opening
	// balance is a transfer. The reference is generated here rather than invented in the driver, so
	// that every row a run leaves behind traces back to the model.
	people := build(t)
	customer, account := people.Treasury()

	if pattern := patternFor(t, "CustomerRef"); !pattern.MatchString(customer) {
		t.Errorf("the treasury customer %q does not match %s", customer, pattern)
	}
	if pattern := patternFor(t, "AccountRef"); !pattern.MatchString(account) {
		t.Errorf("the treasury account %q does not match %s", account, pattern)
	}

	// It sits one past the last customer, so no draw over the whole population can reach it.
	date, err := bankday.ParseDate("2026-08-31")
	if err != nil {
		t.Fatalf("ParseDate: %v", err)
	}
	for seq := int64(0); seq < 20_000; seq++ {
		drawn := people.Draw(11, seq, date)
		if drawn.AccountRef == account || drawn.CounterpartyRef == account {
			t.Fatalf("event %d drew the treasury account", seq)
		}
		if drawn.CustomerRef == customer {
			t.Fatalf("event %d drew the treasury customer", seq)
		}
	}
}

func TestAnAccountReferenceNamesTheCustomerThatHoldsIt(t *testing.T) {
	// Seeding opens an account against its customer, and an action names the counterparty by
	// account only. The inverse has to agree with the generator over the whole population, not over
	// the two examples somebody checked by hand.
	people := build(t)
	date, err := bankday.ParseDate("2026-08-31")
	if err != nil {
		t.Fatalf("ParseDate: %v", err)
	}

	for seq := int64(0); seq < 5_000; seq++ {
		drawn := people.Draw(3, seq, date)
		customer, found := people.CustomerOf(drawn.AccountRef)
		if !found {
			t.Fatalf("%s names no customer", drawn.AccountRef)
		}
		if customer != drawn.CustomerRef {
			t.Fatalf("%s belongs to %s and the draw says %s", drawn.AccountRef, customer, drawn.CustomerRef)
		}
	}

	if _, found := people.CustomerOf("not an account"); found {
		t.Error("read a customer out of something that is not an account reference")
	}
	if _, found := people.CustomerOf("TB000000000000!!"); found {
		t.Error("read a customer out of characters the pattern forbids")
	}
}
