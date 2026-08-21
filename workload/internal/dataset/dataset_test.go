package dataset_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/dataset"
	"github.com/k-napiontek/tessera-bank/workload/internal/model"
)

const committed = "../../../contracts/workload/tessera-day-v1.json"

// A small population and a small scale, so a test emits thousands of lines rather than millions.
// The cohort shares divide 2 000 customers into whole people, which is the condition population.New
// enforces and the reason this number rather than a rounder one.
const (
	testCustomers = 2000
	testScale     = 0.0002
)

func committedModel(t *testing.T) model.Model {
	t.Helper()
	source, err := os.ReadFile(committed)
	if err != nil {
		t.Fatalf("reading the committed model: %v", err)
	}
	loaded, err := model.Decode(source)
	if err != nil {
		t.Fatalf("decoding the committed model: %v", err)
	}
	return loaded
}

func spec(t *testing.T, seed uint64, from, to string) dataset.Spec {
	t.Helper()
	first, err := bankday.ParseDate(from)
	if err != nil {
		t.Fatalf("parsing %q: %v", from, err)
	}
	last, err := bankday.ParseDate(to)
	if err != nil {
		t.Fatalf("parsing %q: %v", to, err)
	}
	return dataset.Spec{
		Model:     committedModel(t),
		From:      first,
		To:        last,
		Seed:      seed,
		Scale:     testScale,
		Customers: testCustomers,
	}
}

func render(t *testing.T, s dataset.Stream) []byte {
	t.Helper()
	var out bytes.Buffer
	for line, err := range s.Lines("PLN") {
		if err != nil {
			t.Fatalf("rendering a line: %v", err)
		}
		out.Write(line)
	}
	return out.Bytes()
}

func stream(t *testing.T, s dataset.Spec) dataset.Stream {
	t.Helper()
	built, err := dataset.New(s)
	if err != nil {
		t.Fatalf("building the stream: %v", err)
	}
	return built
}

// The same claim WP-20 makes about a schedule, made about a dataset: compared as bytes, because two
// structs can be equal while the thing that lands on disk is not.
func TestTheSameSeedAndRangeProduceAByteIdenticalStream(t *testing.T) {
	first := render(t, stream(t, spec(t, 42, "2026-03-02", "2026-03-04")))
	second := render(t, stream(t, spec(t, 42, "2026-03-02", "2026-03-04")))

	if !bytes.Equal(first, second) {
		t.Fatalf("two renderings of the same seed differ: %d bytes against %d", len(first), len(second))
	}
	if len(first) == 0 {
		t.Fatal("the stream is empty, so the comparison above proves nothing")
	}
}

func TestADifferentSeedProducesADifferentStream(t *testing.T) {
	first := render(t, stream(t, spec(t, 42, "2026-03-02", "2026-03-04")))
	second := render(t, stream(t, spec(t, 43, "2026-03-02", "2026-03-04")))

	if bytes.Equal(first, second) {
		t.Fatal("two seeds produced the same stream")
	}
}

// The defect this test exists for: population.Draw takes a business date and uses it only to format
// references, so two dates seeded the same way draw the same customers doing the same things. A year
// built that way gives one small cast of accounts every posting in the dataset. See follow-up F-74.
func TestTwoBusinessDatesDrawDifferentCustomers(t *testing.T) {
	built := stream(t, spec(t, 42, "2026-03-02", "2026-03-03"))

	perDate := map[string][]string{}
	for action := range built.Actions() {
		if len(perDate[action.Date]) < 40 {
			perDate[action.Date] = append(perDate[action.Date], action.CustomerRef+" "+action.Operation)
		}
	}
	if len(perDate) != 2 {
		t.Fatalf("the range covers %d dates, want 2", len(perDate))
	}

	first, second := perDate["2026-03-02"], perDate["2026-03-03"]
	if len(first) < 40 || len(second) < 40 {
		t.Fatalf("too few actions to compare: %d and %d", len(first), len(second))
	}
	same := 0
	for i := range first {
		if first[i] == second[i] {
			same++
		}
	}
	// Some coincidence is expected - the retail cohort is 80% of the population. All forty in the
	// same order is the failure, and it is what an unmixed seed produces.
	if same == len(first) {
		t.Fatal("two business dates drew an identical sequence of customers and operations")
	}
}

func TestARangeThatRunsBackwardsIsRefused(t *testing.T) {
	_, err := dataset.New(spec(t, 42, "2026-03-04", "2026-03-02"))
	if !errors.Is(err, dataset.ErrRange) {
		t.Fatalf("a backwards range gave %v, want ErrRange", err)
	}
}

func TestARangeOfOneDateIsOneDay(t *testing.T) {
	if got := stream(t, spec(t, 42, "2026-03-02", "2026-03-02")).Dates(); got != 1 {
		t.Fatalf("a range from a date to itself covers %d dates, want 1", got)
	}
	if got := stream(t, spec(t, 42, "2026-03-02", "2026-03-04")).Dates(); got != 3 {
		t.Fatalf("2 to 4 March covers %d dates, want 3", got)
	}
}

// A count that does not divide the cohort shares into whole people is a population the model never
// described, and every share a manifest reported would be a share of something else.
func TestACustomerCountThatDoesNotDivideTheCohortsIsRefused(t *testing.T) {
	rounded := spec(t, 42, "2026-03-02", "2026-03-02")
	rounded.Customers = 1001

	if _, err := dataset.New(rounded); err == nil {
		t.Fatal("1 001 customers were accepted, and 0.055 of them is not a whole number of people")
	}
}

func TestANegativeCustomerCountIsRefused(t *testing.T) {
	negative := spec(t, 42, "2026-03-02", "2026-03-02")
	negative.Customers = -1

	if _, err := dataset.New(negative); !errors.Is(err, dataset.ErrCustomers) {
		t.Fatalf("a negative population gave %v, want ErrCustomers", err)
	}
}

// Read out of the model rather than transcribed. A stream naming an operation the estate does not
// serve would be refused at the far end, one row at a time, halfway through a load.
func TestEveryOperationEmittedIsOneTheModelDeclares(t *testing.T) {
	loaded := committedModel(t)
	declared := map[string]bool{}
	for _, cohort := range loaded.Population.Cohorts {
		for name := range cohort.OperationMix {
			declared[name] = true
		}
	}

	seen := map[string]bool{}
	for action := range stream(t, spec(t, 42, "2026-03-02", "2026-03-03")).Actions() {
		if !declared[action.Operation] {
			t.Fatalf("the stream drew %q, which no cohort declares", action.Operation)
		}
		seen[action.Operation] = true
	}
	if len(seen) < 5 {
		t.Fatalf("only %d distinct operations were drawn, which is too few to have checked anything", len(seen))
	}
}

func TestTheHeaderIsTheFirstLineAndNamesTheTreasury(t *testing.T) {
	built := stream(t, spec(t, 42, "2026-03-02", "2026-03-02"))
	rendered := render(t, built)

	firstLine := rendered[:bytes.IndexByte(rendered, '\n')+1]
	var header dataset.Header
	if err := json.Unmarshal(firstLine, &header); err != nil {
		t.Fatalf("the first line is not a header: %v", err)
	}
	if header.Kind != dataset.KindPopulation {
		t.Fatalf("the first line is a %q, want %q", header.Kind, dataset.KindPopulation)
	}

	treasuryCustomer, treasuryAccount := built.People().Treasury()
	if header.TreasuryCustomerRef != treasuryCustomer || header.TreasuryAccountRef != treasuryAccount {
		t.Fatalf("the header names %s/%s as the treasury, the population generates %s/%s",
			header.TreasuryCustomerRef, header.TreasuryAccountRef, treasuryCustomer, treasuryAccount)
	}
	if header.Customers != testCustomers {
		t.Fatalf("the header reports %d customers, want %d", header.Customers, testCustomers)
	}
	if header.ModelDigest == "" {
		t.Fatal("the header carries no model digest, so a load could not say which model it came from")
	}
}

// Money leaves the engine as int64 minor units and arrives as int64 minor units. A float on this
// path is the WP-04 truncation in a different language.
func TestAMoneyMovingActionCarriesMinorUnitsAndACurrency(t *testing.T) {
	moving := 0
	for action := range stream(t, spec(t, 42, "2026-03-02", "2026-03-02")).Actions() {
		if action.AmountMinor == 0 {
			continue
		}
		moving++
		if action.AmountMinor < 0 {
			t.Fatalf("%s carries a negative amount %d", action.TransferRef, action.AmountMinor)
		}
		if action.Currency == "" {
			t.Fatalf("%s carries an amount and no currency", action.TransferRef)
		}
		if action.CounterpartyRef == "" || action.TransferRef == "" {
			t.Fatalf("%s moves money with no counterparty or no reference", action.AccountRef)
		}
	}
	if moving == 0 {
		t.Fatal("no action in the stream moved money, so nothing above was checked")
	}
}
