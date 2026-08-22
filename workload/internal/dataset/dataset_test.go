package dataset_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"sort"
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/arrivals"
	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/dataset"
	"github.com/k-napiontek/tessera-bank/workload/internal/model"
)

const committed = "../../../contracts/workload/tessera-day-v1.json"

// What the driver half decides. Spelled out here rather than imported, because internal/seeding is
// on the other side of the purity boundary and this package must not reach across it - the command
// is where the two meet.
//
// OpeningBalanceMinor is seeding.Opening's answer for the committed model - twenty times the largest
// transfer any cohort can draw, which is 5 000 000.00 in the corporate cohort. Written out rather
// than computed for the same reason: this side must not hold a second copy of the arithmetic. The
// fixture test at the bottom of this file is what holds the command to the same figure.
var testBank = dataset.Bank{
	BaseCurrency:        "PLN",
	CustomerAccountType: "LIABILITY",
	TreasuryAccountType: "ASSET",
	OpeningBalanceMinor: 10_000_000_000,
}

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
	for line, err := range s.Lines(testBank) {
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

// **F-98.** The stream used to carry no opening balance, so every consumer invented one: the driver
// funded twenty times the largest drawable transfer, the loader two hundred times a cohort median,
// and the stratum-0 writer a constant. Three answers, and a reconciliation between any two of them
// breaks on every account. The figure now travels with the day.
//
// Supplied by the command out of internal/seeding, never computed here - the same rule BaseCurrency
// follows, and for the same reason: a second copy of a WP-21 decision is a second bank.
func TestTheHeaderCarriesTheOpeningBalanceEveryConsumerFunds(t *testing.T) {
	built := stream(t, spec(t, 42, "2026-03-02", "2026-03-02"))
	rendered := render(t, built)

	firstLine := rendered[:bytes.IndexByte(rendered, '\n')+1]
	var header dataset.Header
	if err := json.Unmarshal(firstLine, &header); err != nil {
		t.Fatalf("the first line is not a header: %v", err)
	}
	if header.OpeningBalanceMinor != testBank.OpeningBalanceMinor {
		t.Fatalf("the header carries an opening balance of %d, the bank was built with %d",
			header.OpeningBalanceMinor, testBank.OpeningBalanceMinor)
	}

	// The wire name, not just the Go field. The loader parses this stream by property name and
	// refuses one it does not know, so a rename here is a load that fails on the first line.
	var wire map[string]any
	if err := json.Unmarshal(firstLine, &wire); err != nil {
		t.Fatalf("the header is not an object: %v", err)
	}
	if _, found := wire["openingBalanceMinor"]; !found {
		t.Fatalf("the header has no openingBalanceMinor property; it carries %v", keysOf(wire))
	}
}

// The cut-off, on the emitter's side of the strand.
//
// A two-phase day drives the online window and then writes the movement file from the same stream.
// Those two have to end at the same instant: a file carrying transfers the driver never sent puts
// them on the master and not in the ledger, and ADR 0015 makes the file the reconciliation cut-off,
// so the difference is reported as drift rather than as timing. cmd/workload-run bounds its schedule
// by business minute; this is the same bound, on the same events, from the same seed.
func TestActionsCanBeBoundedByTheSameMinutesTheDriverRuns(t *testing.T) {
	const cutOff = 1200 // the online-cut-off instant the day contract declares

	whole := spec(t, 42, "2026-03-02", "2026-03-02")
	bounded := whole
	bounded.ToMinute = cutOff

	before := 0
	for action := range stream(t, whole).Actions() {
		if action.AtMillis < int64(cutOff)*60_000 {
			before++
		}
	}
	if before == 0 {
		t.Fatal("no action falls before the cut-off, so this proves nothing")
	}

	got := 0
	for action := range stream(t, bounded).Actions() {
		if action.AtMillis >= int64(cutOff)*60_000 {
			t.Fatalf("action %d is at minute %d, past the cut-off at %d",
				action.Seq, action.AtMillis/60_000, cutOff)
		}
		got++
	}
	if got != before {
		t.Fatalf("the bounded stream yields %d actions and the whole day has %d before the cut-off",
			got, before)
	}
}

// Zero keeps the whole day, the same convention Customers already uses. Every caller that predates
// the bound therefore keeps emitting exactly what it emitted.
func TestAZeroCutOffKeepsTheWholeDay(t *testing.T) {
	whole := 0
	for range stream(t, spec(t, 42, "2026-03-02", "2026-03-02")).Actions() {
		whole++
	}
	bounded := spec(t, 42, "2026-03-02", "2026-03-02")
	bounded.ToMinute = 0
	got := 0
	for range stream(t, bounded).Actions() {
		got++
	}
	if got != whole {
		t.Fatalf("a zero cut-off yielded %d actions and the whole day is %d", got, whole)
	}
}

func TestACutOffOutsideTheDayIsRefused(t *testing.T) {
	bad := spec(t, 42, "2026-03-02", "2026-03-02")
	bad.ToMinute = 1441
	if _, err := dataset.New(bad); !errors.Is(err, dataset.ErrCutOff) {
		t.Fatalf("a cut-off past the end of the day gave %v, want ErrCutOff", err)
	}
}

// **F-102.** The emitter and the driver drew different days from the same seed, and nothing noticed
// because nothing had ever compared them.
//
// seedFor derives a per-date seed so that a year of dates does not give one small cast of accounts
// every day's history - see the dateStream comment. cmd/workload-run has no such problem: it drives
// one date and draws it from the run seed itself. The two are therefore different days whenever they
// are pointed at the same one, and a two-phase run reconciles a master built from one against a
// ledger driven by the other - drift on every account, and a control measuring nothing.
//
// DriverSeed is the emitter drawing the date the way the driver draws it. Off by default, so every
// stream that predates it is byte-identical.
func TestADriverSeededStreamDrawsWhatTheDriverWouldDraw(t *testing.T) {
	const seed = 42
	day, err := bankday.ParseDate("2026-03-02")
	if err != nil {
		t.Fatalf("parsing the date: %v", err)
	}

	matched := spec(t, seed, "2026-03-02", "2026-03-02")
	matched.DriverSeed = true
	built := stream(t, matched)

	// What cmd/workload-run walks: the process at the run seed, drawn at the run seed.
	curve, err := committedModel(t).Curve()
	if err != nil {
		t.Fatalf("resolving the curve: %v", err)
	}
	process, err := arrivals.New(curve, day, testScale)
	if err != nil {
		t.Fatalf("arrivals.New: %v", err)
	}

	expected := []string{}
	for event := range process.Events(seed) {
		drawn := built.People().Draw(seed, event.Seq, day)
		expected = append(expected, drawn.AccountRef)
	}
	if len(expected) == 0 {
		t.Fatal("the driver would send nothing, so this proves nothing")
	}

	got := []string{}
	for action := range built.Actions() {
		got = append(got, action.AccountRef)
	}
	if len(got) != len(expected) {
		t.Fatalf("the stream draws %d actions and the driver would send %d", len(got), len(expected))
	}
	for index := range expected {
		if got[index] != expected[index] {
			t.Fatalf("action %d lands on %s and the driver would send it to %s",
				index, got[index], expected[index])
		}
	}
}

// The default is unchanged, which is what keeps every committed load and baseline valid.
func TestWithoutDriverSeedTheStreamKeepsItsPerDateDerivation(t *testing.T) {
	plain := render(t, stream(t, spec(t, 42, "2026-03-02", "2026-03-02")))

	matched := spec(t, 42, "2026-03-02", "2026-03-02")
	matched.DriverSeed = true
	if bytes.Equal(plain, render(t, stream(t, matched))) {
		t.Fatal("the two draws are identical, so the flag does nothing and F-102 was misdiagnosed")
	}
}

// A range drawn from one seed gives every date the same customers doing the same things, which is
// exactly what seedFor exists to prevent. Refused rather than allowed to produce a year with one
// day in it repeated.
func TestDriverSeedIsRefusedForARangeOfMoreThanOneDate(t *testing.T) {
	bad := spec(t, 42, "2026-03-02", "2026-03-03")
	bad.DriverSeed = true
	if _, err := dataset.New(bad); !errors.Is(err, dataset.ErrDriverSeed) {
		t.Fatalf("a multi-date driver-seeded range gave %v, want ErrDriverSeed", err)
	}
}

func keysOf(object map[string]any) []string {
	names := make([]string, 0, len(object))
	for name := range object {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
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

// A loader stands the estate up before it posts to it. If an account could first appear as the
// counterparty of a transfer, it would have no opening balance and no opened date - and the position
// report bounds accounts by opened_date, so it would be missing from every report that covered the
// day it started trading.
func TestEveryAccountIsOpenedBeforeTheFirstAction(t *testing.T) {
	built := stream(t, spec(t, 42, "2026-03-02", "2026-03-02"))

	opened := map[string]bool{}
	for open := range built.Opens(testBank) {
		if opened[open.AccountRef] {
			t.Fatalf("%s is opened twice", open.AccountRef)
		}
		opened[open.AccountRef] = true
	}
	if len(opened) != testCustomers*2+1 {
		t.Fatalf("%d accounts were opened, want %d customers x 2 plus the treasury",
			len(opened), testCustomers)
	}

	for action := range built.Actions() {
		if !opened[action.AccountRef] {
			t.Fatalf("%s acts and was never opened", action.AccountRef)
		}
		if action.CounterpartyRef != "" && !opened[action.CounterpartyRef] {
			t.Fatalf("%s is a counterparty and was never opened", action.CounterpartyRef)
		}
	}
}

// The treasury is the one account no customer owns, and it is an asset: debiting it to fund a
// customer increases it, so it never needs an overdraft. Exactly one account may say so.
func TestExactlyOneAccountIsTheTreasuryAndItIsAnAsset(t *testing.T) {
	built := stream(t, spec(t, 42, "2026-03-02", "2026-03-02"))

	treasuries := 0
	for open := range built.Opens(testBank) {
		if !open.Treasury {
			if open.AccountType != testBank.CustomerAccountType {
				t.Fatalf("%s is opened as %s, want %s", open.AccountRef, open.AccountType,
					testBank.CustomerAccountType)
			}
			if open.Cohort == "" {
				t.Fatalf("%s belongs to no cohort", open.AccountRef)
			}
			continue
		}
		treasuries++
		if open.AccountType != testBank.TreasuryAccountType {
			t.Fatalf("the treasury is opened as %s, want %s", open.AccountType, testBank.TreasuryAccountType)
		}
	}
	if treasuries != 1 {
		t.Fatalf("%d accounts claim to be the treasury, want exactly 1", treasuries)
	}
}

// The loader scales an opening balance against the size of the money a cohort moves, and the actions
// carry only a cohort name. A cohort in the stream that the header does not describe would be an
// account opened at whatever the loader defaulted to.
func TestTheHeaderDescribesEveryCohortTheStreamNames(t *testing.T) {
	built := stream(t, spec(t, 42, "2026-03-02", "2026-03-02"))

	medians := map[string]int64{}
	for _, cohort := range built.Header(testBank).Cohorts {
		medians[cohort.ID] = cohort.MedianAmountMinor
	}
	if len(medians) == 0 {
		t.Fatal("the header describes no cohorts")
	}
	for id, median := range medians {
		if median <= 0 {
			t.Fatalf("cohort %s has a median amount of %d", id, median)
		}
	}
	for open := range built.Opens(testBank) {
		if open.Treasury {
			continue
		}
		if _, found := medians[open.Cohort]; !found {
			t.Fatalf("%s is in cohort %q, which the header does not describe", open.AccountRef, open.Cohort)
		}
	}
}

// The fixture services/ledger-loader tests against is generated by this command, and this test is
// what stops the two drifting apart.
//
// There is no schema between the emitter and the loader - it is one contract expressed in two
// languages - so a field renamed here and not there would be a load that quietly dropped a column.
// The loader's own reader refuses an unknown field, which catches the addition; this catches the
// removal, the rename and the reordering, by regenerating the fixture and comparing bytes. Regenerate
// it with the command in the loader's README when this test fails on purpose.
func TestTheLoaderFixtureIsWhatThisCommandProduces(t *testing.T) {
	const fixture = "../../../services/ledger-loader/src/test/resources/sample-stream.ndjson"

	committed, err := os.ReadFile(fixture)
	if err != nil {
		t.Fatalf("reading the loader's fixture: %v", err)
	}

	pinned := spec(t, 42, "2026-03-02", "2026-03-03")
	pinned.Scale = 0.0000075
	pinned.Customers = 200
	regenerated := render(t, stream(t, pinned))

	if !bytes.Equal(committed, regenerated) {
		t.Fatalf("the emitter no longer produces the fixture ledger-loader tests against: "+
			"%d bytes committed against %d regenerated. Regenerate %s.",
			len(committed), len(regenerated), fixture)
	}
}
