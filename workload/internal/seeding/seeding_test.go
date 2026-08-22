package seeding_test

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/arrivals"
	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
	"github.com/k-napiontek/tessera-bank/workload/internal/seeding"
)

// answers is a fake estate: it records what it was sent and answers however the test needs.
type answers struct {
	mu   sync.Mutex
	sent []client.Request
	// status decides the answer, by operation and then by call.
	reply func(client.Request, int) client.Result
}

func (a *answers) Send(_ context.Context, request client.Request, _ time.Time) client.Result {
	a.mu.Lock()
	a.sent = append(a.sent, request)
	index := len(a.sent)
	a.mu.Unlock()
	return a.reply(request, index)
}

func (a *answers) requests() []client.Request {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]client.Request(nil), a.sent...)
}

func accepting() *answers {
	return &answers{reply: func(request client.Request, _ int) client.Result {
		if request.Operation == "openAccount" {
			return client.Result{Outcome: client.Posted, Status: 201}
		}
		return client.Result{Outcome: client.Posted, Status: 201}
	}}
}

func spec() population.Spec {
	return population.Spec{
		Size:                2_000,
		AccountsPerCustomer: 2,
		Cohorts: []population.Cohort{
			{
				ID: "retail", Share: 0.9, EventsPerCustomerPerDay: 15,
				Amount:     population.AmountSpec{MedianMinor: 12_000, Sigma: 1.1, MinMinor: 100, MaxMinor: 5_000_000},
				Currencies: []population.Weighted[money.Currency]{{Value: "PLN", Weight: 0.9}, {Value: "EUR", Weight: 0.1}},
				Operations: []population.Weighted[string]{
					{Value: "getBalance", Weight: 0.5}, {Value: "createTransfer", Weight: 0.5},
				},
			},
			{
				ID: "corporate", Share: 0.1, EventsPerCustomerPerDay: 36,
				Amount:     population.AmountSpec{MedianMinor: 1_250_000, Sigma: 1.4, MinMinor: 10_000, MaxMinor: 500_000_000},
				Currencies: []population.Weighted[money.Currency]{{Value: "EUR", Weight: 0.6}, {Value: "PLN", Weight: 0.4}},
				Operations: []population.Weighted[string]{
					{Value: "createTransfer", Weight: 0.7}, {Value: "getStatement", Weight: 0.3},
				},
			},
		},
	}
}

func people(t *testing.T) population.Population {
	t.Helper()
	built, err := population.New(spec())
	if err != nil {
		t.Fatalf("population.New: %v", err)
	}
	return built
}

func date(t *testing.T) bankday.Date {
	t.Helper()
	parsed, err := bankday.ParseDate("2026-08-31")
	if err != nil {
		t.Fatalf("ParseDate: %v", err)
	}
	return parsed
}

// schedule is a handful of events, which is all a seeding test needs: the walk is the same one the
// run performs and the arrival process has its own tests.
func schedule(count int) func(func(arrivals.Event) bool) {
	return func(yield func(arrivals.Event) bool) {
		for seq := int64(0); seq < int64(count); seq++ {
			if !yield(arrivals.Event{Seq: seq, At: time.Duration(seq) * time.Second}) {
				return
			}
		}
	}
}

func TestTheEstateIsOpenedInTheCurrencyTheModelWeightsHeaviest(t *testing.T) {
	// Computed, not assumed. A model whose traffic is mostly EUR must not seed a PLN estate and
	// then report every transfer as a substitution.
	base, err := seeding.BaseCurrency(people(t))
	if err != nil {
		t.Fatalf("BaseCurrency: %v", err)
	}
	if base != "PLN" {
		t.Errorf("opened in %s, and the heavier cohort draws PLN nine times in ten", base)
	}
}

func TestTheCurrencyIsWeightedByEventsRatherThanByCohortCount(t *testing.T) {
	// A corporate cohort is 1.5% of the customers and generates far more than 1.5% of the events.
	// Averaging the cohorts flat would let a small, quiet cohort choose the currency of the estate.
	shifted := spec()
	shifted.Cohorts[0].Currencies = []population.Weighted[money.Currency]{{Value: "EUR", Weight: 1}}
	shifted.Cohorts[1].Currencies = []population.Weighted[money.Currency]{{Value: "PLN", Weight: 1}}
	built, err := population.New(shifted)
	if err != nil {
		t.Fatalf("population.New: %v", err)
	}

	base, err := seeding.BaseCurrency(built)
	if err != nil {
		t.Fatalf("BaseCurrency: %v", err)
	}
	if base != "EUR" {
		t.Errorf("opened in %s, and the cohort generating most of the events draws EUR", base)
	}
}

func TestTheOpeningBalanceCoversTheLargestTransferTheModelCanDraw(t *testing.T) {
	// A run that turns into a study of INSUFFICIENT_FUNDS is measuring the fixture.
	opening, err := seeding.Opening(people(t), "PLN")
	if err != nil {
		t.Fatalf("Opening: %v", err)
	}
	if opening.Currency != "PLN" {
		t.Errorf("the opening balance is in %s", opening.Currency)
	}
	if opening.Minor < 500_000_000 {
		t.Errorf("opens with %d and the model can draw %d in one transfer", opening.Minor, 500_000_000)
	}
}

func TestThePlanIsEveryAccountTheScheduleTouchesAndNothingElse(t *testing.T) {
	// No more, which would leave the ledger holding accounts the run never used; no less, which
	// would spend the run collecting 404s on accounts nobody opened.
	built := people(t)
	plan := seeding.Plan(built, schedule(400), 7, date(t))

	if len(plan) < 2 {
		t.Fatalf("the plan holds %d accounts", len(plan))
	}
	planned := map[string]bool{}
	for _, account := range plan {
		if planned[account.AccountRef] {
			t.Errorf("%s is opened twice", account.AccountRef)
		}
		planned[account.AccountRef] = true
	}

	for seq := int64(0); seq < 400; seq++ {
		action := built.Draw(7, seq, date(t))
		if !planned[action.AccountRef] {
			t.Errorf("event %d uses %s and the plan does not open it", seq, action.AccountRef)
		}
		if action.CounterpartyRef != "" && !planned[action.CounterpartyRef] {
			t.Errorf("event %d pays %s and the plan does not open it", seq, action.CounterpartyRef)
		}
	}
}

// The plan a two-phase day needs, and the reason it is a second function rather than a wider Plan.
//
// A run that is going to be reconciled against a stratum-0 master cannot seed only what its schedule
// touches: mainframe/data/generate.py writes an ACCTREC for every account the stream opens, so every
// account the day happened not to touch would be a MISSING_IN_LEDGER break - tens of thousands of
// them, burying whatever the reconciliation was meant to find. The two sides have to hold the same
// estate, and this is the half that makes them.
//
// Plan stays exactly as it is, because for a run that is *not* reconciled it is the right answer:
// opening 2.4 million accounts to drive nine thousand of them is a study of the seeding phase.
func TestThePlanForAWholePopulationHoldsEveryAccountItDeclares(t *testing.T) {
	built := people(t)
	plan := seeding.PlanAll(built)

	counted := 0
	for range built.Accounts() {
		counted++
	}
	if len(plan) != counted {
		t.Fatalf("the population declares %d accounts and the plan holds %d", counted, len(plan))
	}

	planned := map[string]bool{}
	for _, account := range plan {
		if planned[account.AccountRef] {
			t.Errorf("%s is opened twice", account.AccountRef)
		}
		planned[account.AccountRef] = true
	}
	for holding := range built.Accounts() {
		if !planned[holding.AccountRef] {
			t.Errorf("the population declares %s and the plan does not open it", holding.AccountRef)
		}
	}

	_, treasury := built.Treasury()
	if plan[0].AccountRef != treasury || plan[0].Type != seeding.Asset {
		t.Errorf("the plan starts with %s as %s, the treasury is %s and must be an %s",
			plan[0].AccountRef, plan[0].Type, treasury, seeding.Asset)
	}
	for _, account := range plan[1:] {
		if account.Type != seeding.Liability {
			t.Errorf("%s is opened as %s, and a customer account is a liability of the bank",
				account.AccountRef, account.Type)
		}
	}
}

// Whatever the schedule touches, the whole-population plan holds too. The two-phase run relies on
// this: the movement file is drawn from the same stream, so an account the day pays that the wider
// plan had missed would be an unknown account at stratum 0 and a 404 at the edge.
func TestTheWholePopulationPlanIsASupersetOfTheSchedulePlan(t *testing.T) {
	built := people(t)
	whole := map[string]bool{}
	for _, account := range seeding.PlanAll(built) {
		whole[account.AccountRef] = true
	}
	for _, account := range seeding.Plan(built, schedule(400), 7, date(t)) {
		if !whole[account.AccountRef] {
			t.Errorf("the schedule touches %s and the whole-population plan omits it", account.AccountRef)
		}
	}
}

func TestThePlanOpensTheTreasuryFirstAndAsAnAsset(t *testing.T) {
	// Funding debits it. An ASSET account grows when it is debited, so the bank's own account never
	// needs an overdraft - and a LIABILITY treasury would be refused the moment it funded anything.
	built := people(t)
	plan := seeding.Plan(built, schedule(50), 3, date(t))

	_, treasury := built.Treasury()
	if plan[0].AccountRef != treasury {
		t.Errorf("the plan starts with %s and the treasury is %s", plan[0].AccountRef, treasury)
	}
	if plan[0].Type != seeding.Asset {
		t.Errorf("the treasury is opened as %s", plan[0].Type)
	}
	for _, account := range plan[1:] {
		if account.Type != seeding.Liability {
			t.Errorf("%s is opened as %s, and a customer account is a liability of the bank",
				account.AccountRef, account.Type)
		}
	}
}

func TestSeedingOpensEveryAccountAndFundsItFromTheTreasury(t *testing.T) {
	built := people(t)
	plan := seeding.Plan(built, schedule(60), 5, date(t))
	fake := accepting()

	report, err := seeding.Run(context.Background(), fake, plan, amount(t, 1_000_000), 4)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if report.Opened != len(plan) {
		t.Errorf("opened %d of %d", report.Opened, len(plan))
	}
	if report.Funded != len(plan)-1 {
		t.Errorf("funded %d, and the treasury funds itself from nowhere", report.Funded)
	}

	_, treasury := built.Treasury()
	for _, request := range fake.requests() {
		if request.Operation != "createTransfer" {
			continue
		}
		var body struct {
			DebitAccountRef string `json:"debitAccountRef"`
		}
		if err := json.Unmarshal(request.Body, &body); err != nil {
			t.Fatalf("funding body: %v", err)
		}
		if body.DebitAccountRef != treasury {
			t.Errorf("funding debits %s rather than the treasury", body.DebitAccountRef)
		}
		if request.Key == "" {
			t.Error("funding went out with no idempotency key, so a second seeding pass credits twice")
		}
	}
}

func TestTheTreasuryIsOpenedBeforeAnythingIsFundedFromIt(t *testing.T) {
	// A worker that raced ahead would debit an account that did not exist yet, and the run would
	// start against an estate holding no money at all.
	built := people(t)
	plan := seeding.Plan(built, schedule(40), 9, date(t))
	fake := accepting()

	if _, err := seeding.Run(context.Background(), fake, plan, amount(t, 500_000), 8); err != nil {
		t.Fatalf("Run: %v", err)
	}

	sent := fake.requests()
	if sent[0].Operation != "openAccount" {
		t.Fatalf("the first request is %s", sent[0].Operation)
	}
	_, treasury := built.Treasury()
	if !strings.Contains(string(sent[0].Body), treasury) {
		t.Errorf("the first request opens something other than the treasury: %s", sent[0].Body)
	}
}

func TestAnAccountThatAlreadyExistsIsNotAFailure(t *testing.T) {
	// openAccount's own description: "opening a reference that already exists is a 409 rather than
	// a second account". Re-running a seeded estate has to stay cheap, which is what makes the boot
	// script re-runnable.
	built := people(t)
	plan := seeding.Plan(built, schedule(30), 2, date(t))
	fake := &answers{reply: func(request client.Request, _ int) client.Result {
		if request.Operation == "openAccount" {
			return client.Result{Outcome: client.Rejected, Status: 409}
		}
		return client.Result{Outcome: client.Replayed, Status: 200}
	}}

	report, err := seeding.Run(context.Background(), fake, plan, amount(t, 500_000), 2)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if report.AlreadyOpen != len(plan) || report.Opened != 0 {
		t.Errorf("opened %d, already open %d, of %d", report.Opened, report.AlreadyOpen, len(plan))
	}
	if report.Replayed != len(plan)-1 {
		t.Errorf("replayed %d funding transfers", report.Replayed)
	}
	if report.Failed != 0 {
		t.Errorf("%d failures against an estate that was simply already seeded", report.Failed)
	}
}

func TestARunAgainstAnEstateThatCannotBeSeededStopsRatherThanStarting(t *testing.T) {
	// Every event would be a 404 on an account nobody opened, and the report would read as the
	// ledger refusing traffic it was never given a chance to serve.
	built := people(t)
	plan := seeding.Plan(built, schedule(30), 4, date(t))
	fake := &answers{reply: func(request client.Request, index int) client.Result {
		if request.Operation == "openAccount" && index > 3 {
			return client.Result{Outcome: client.Unknown, Status: 503}
		}
		return client.Result{Outcome: client.Posted, Status: 201}
	}}

	report, err := seeding.Run(context.Background(), fake, plan, amount(t, 500_000), 1)
	if err == nil {
		t.Fatal("seeding reported success against an estate that refused to open accounts")
	}
	if report.Failed == 0 {
		t.Error("the report counts no failures")
	}
}

func TestATreasuryThatCannotBeOpenedStopsImmediately(t *testing.T) {
	built := people(t)
	plan := seeding.Plan(built, schedule(20), 6, date(t))
	fake := &answers{reply: func(client.Request, int) client.Result {
		return client.Result{Outcome: client.Unknown, Status: 500}
	}}

	if _, err := seeding.Run(context.Background(), fake, plan, amount(t, 100), 4); err == nil {
		t.Fatal("carried on funding from a treasury that does not exist")
	}
	if count := len(fake.requests()); count != 1 {
		t.Errorf("sent %d requests after the treasury failed to open", count)
	}
}

func amount(t *testing.T, minor int64) money.Amount {
	t.Helper()
	built, err := money.New(minor, "PLN")
	if err != nil {
		t.Fatalf("money.New: %v", err)
	}
	return built
}
