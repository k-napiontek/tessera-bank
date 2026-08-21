// Package population draws who is acting and what they are doing.
//
// The cohort model is load-bearing rather than decorative. The gateway limits 10 requests per second
// with a burst of 20, keyed by token subject plus route class, per instance - ADR 0006. One
// synthetic customer therefore cannot generate meaningful load at all, and raising the limit for a
// load run would be measuring a gateway nobody deploys. A population of thousands of distinct
// subjects is both the realistic shape and the only working one.
//
// Every reference is drawn to the pattern the ledger's own OpenAPI document declares, and the test
// reads those patterns from that document rather than from a copy. There are four formats and they
// are genuinely different; a single "reference" helper applied to all four is the mistake, and three
// of the four would then be refused at the far end.
//
// Nothing here can carry personal data. A customer is an index and a pseudonymous reference, and
// there is no field a name could be written into - the same statement the workload schema makes, in
// the code that consumes it.
package population

import (
	"errors"
	"fmt"
	"math"
	"math/rand/v2"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
)

// This file names float64, and internal/money/source_test.go requires a recorded reason. The reason
// is the log-normal draw: a distribution is continuous. The float ends at drawMinor, which returns
// int64 minor units and hands nothing else back - there is no float64 anywhere in Action.

// The streams this package seeds from. Distinct from arrivals' stream so that the same run seed
// gives two independent sequences rather than one replayed twice.
const drawStream uint64 = 0x706f_7075_6c61_7469 // "populati"

// Weighted is a value and the weight it is drawn with.
type Weighted[T any] struct {
	Value  T
	Weight float64
}

// AmountSpec is a cohort's transfer size: log-normal in shape, clamped to a declared range, and
// stated in minor units throughout.
type AmountSpec struct {
	MedianMinor int64
	Sigma       float64
	MinMinor    int64
	MaxMinor    int64
}

// Cohort is a class of customer and how it behaves.
type Cohort struct {
	ID                      string
	Share                   float64
	EventsPerCustomerPerDay int
	Amount                  AmountSpec
	Currencies              []Weighted[money.Currency]
	Operations              []Weighted[string]
}

// Spec is the population half of a workload model.
type Spec struct {
	Size                int
	AccountsPerCustomer int
	Cohorts             []Cohort
}

// Action is one customer doing one thing. Fields that do not apply to the operation are zero.
type Action struct {
	Cohort      string
	CustomerRef string
	AccountRef  string
	Operation   string

	// Set only when the operation moves money.
	CounterpartyRef string
	TransferRef     string
	MovementRefs    [2]string
	Amount          money.Amount

	// Set only for the hold operations.
	HoldRef string
}

// MovesMoney reports whether this action carries an amount.
func (a Action) MovesMoney() bool { return a.Amount.Minor != 0 }

// moneyMoving is the set of operations that carry an amount and a counterparty. Read against the
// eleven operationIds in contracts/openapi/ledger-core.yaml; a test proves every operation drawn is
// one of them, so an operation absent from this map is a read rather than an unknown.
var moneyMoving = map[string]bool{
	"createTransfer":  true,
	"reverseTransfer": true,
	"placeHold":       true,
	"captureHold":     true,
}

// holdBearing is the set of operations that name a hold.
var holdBearing = map[string]bool{
	"placeHold":   true,
	"captureHold": true,
	"releaseHold": true,
	"listHolds":   true,
}

// cohortBlock is a cohort's slice of the customer index space, and its share of the day's events.
type cohortBlock struct {
	Cohort
	firstCustomer int
	customers     int
	eventWeight   float64
}

// Population draws actions. It holds no connection, no file handle and no path.
type Population struct {
	spec        Spec
	blocks      []cohortBlock
	eventTotal  float64
	accountBase int
}

var (
	// ErrShares reports cohort shares that do not add to 1.
	ErrShares = errors.New("population: cohort shares must add to 1")
	// ErrEmpty reports a population with nobody or nothing to do in it.
	ErrEmpty = errors.New("population: needs customers, accounts and at least one cohort")
)

const sharesTolerance = 1e-9

// New validates a spec and lays the cohorts out over the customer index space.
func New(spec Spec) (Population, error) {
	if spec.Size <= 0 || spec.AccountsPerCustomer <= 0 || len(spec.Cohorts) == 0 {
		return Population{}, ErrEmpty
	}

	shares := 0.0
	for _, cohort := range spec.Cohorts {
		shares += cohort.Share
	}
	if math.Abs(shares-1) > sharesTolerance {
		return Population{}, fmt.Errorf("%w: they add to %v", ErrShares, shares)
	}

	people := Population{spec: spec}
	next := 0
	for _, cohort := range spec.Cohorts {
		if err := validateCohort(cohort, spec.Size); err != nil {
			return Population{}, err
		}
		members := int(math.Round(cohort.Share * float64(spec.Size)))
		weight := cohort.Share * float64(cohort.EventsPerCustomerPerDay)
		people.blocks = append(people.blocks, cohortBlock{
			Cohort:        cohort,
			firstCustomer: next,
			customers:     members,
			eventWeight:   weight,
		})
		people.eventTotal += weight
		next += members
	}
	if next != spec.Size {
		return Population{}, fmt.Errorf(
			"population: the cohorts lay out %d customers and the population is %d", next, spec.Size)
	}
	return people, nil
}

func validateCohort(cohort Cohort, size int) error {
	if cohort.ID == "" {
		return errors.New("population: a cohort with no id")
	}
	if cohort.EventsPerCustomerPerDay <= 0 {
		return fmt.Errorf("population: cohort %q generates no events", cohort.ID)
	}
	members := cohort.Share * float64(size)
	if math.Abs(members-math.Round(members)) > 1e-6 {
		return fmt.Errorf(
			"population: cohort %q takes %v of %d customers, which is not a whole number of people",
			cohort.ID, cohort.Share, size)
	}
	if len(cohort.Currencies) == 0 {
		return fmt.Errorf("population: cohort %q has no currencies", cohort.ID)
	}
	for _, currency := range cohort.Currencies {
		if !money.Valid(currency.Value) {
			return fmt.Errorf("population: cohort %q draws %q, which this estate cannot carry: %w",
				cohort.ID, currency.Value, money.ErrCurrency)
		}
	}
	if len(cohort.Operations) == 0 {
		return fmt.Errorf("population: cohort %q has no operations", cohort.ID)
	}
	if err := positiveWeights(cohort.ID, "currency", cohort.Currencies); err != nil {
		return err
	}
	if err := positiveWeights(cohort.ID, "operation", cohort.Operations); err != nil {
		return err
	}
	amount := cohort.Amount
	if amount.MinMinor <= 0 || amount.MaxMinor < amount.MinMinor {
		return fmt.Errorf("population: cohort %q has an empty amount range", cohort.ID)
	}
	if amount.MedianMinor < amount.MinMinor || amount.MedianMinor > amount.MaxMinor {
		return fmt.Errorf(
			"population: cohort %q has a median of %d outside its range [%d, %d]",
			cohort.ID, amount.MedianMinor, amount.MinMinor, amount.MaxMinor)
	}
	if amount.Sigma <= 0 {
		return fmt.Errorf("population: cohort %q has a sigma of %v, so every draw is the median",
			cohort.ID, amount.Sigma)
	}
	return nil
}

func positiveWeights[T any](cohort, kind string, weighted []Weighted[T]) error {
	for _, entry := range weighted {
		if entry.Weight <= 0 {
			return fmt.Errorf("population: cohort %q gives a %s the weight %v",
				cohort, kind, entry.Weight)
		}
	}
	return nil
}

// Size is the number of distinct customers.
func (p Population) Size() int { return p.spec.Size }

// Cohorts returns the cohorts, in the order the model declared them.
func (p Population) Cohorts() []Cohort { return p.spec.Cohorts }

// EventShare is a cohort's fraction of the day's events, which is its headcount weighted by how
// busy its customers are. Reported because it is the figure a reader expects to be the share and
// is not.
func (p Population) EventShare(id string) float64 {
	for _, block := range p.blocks {
		if block.ID == id {
			return block.eventWeight / p.eventTotal
		}
	}
	return 0
}

// Draw builds the action for one scheduled event.
//
// Seeded per event rather than per run, so that a driver may fan the schedule out across workers
// and still produce the day the manifest describes. A generator threaded through one mutable source
// would make the result depend on which worker reached it first, and a manifest promising
// reproducibility would be promising something the driver cannot deliver.
func (p Population) Draw(seed uint64, seq int64, date bankday.Date) Action {
	random := rand.New(rand.NewPCG(seed^drawStream, uint64(seq)))

	block := p.pickCohort(random)
	customer := block.firstCustomer + random.IntN(block.customers)
	slot := random.IntN(p.spec.AccountsPerCustomer)

	action := Action{
		Cohort:      block.ID,
		CustomerRef: customerRef(customer),
		AccountRef:  accountRef(customer, slot, p.spec.AccountsPerCustomer),
		Operation:   pick(random, block.Operations),
	}

	if holdBearing[action.Operation] {
		action.HoldRef = holdRef(date, seq)
	}

	if moneyMoving[action.Operation] {
		// A different customer, so the transfer is never refused SAME_ACCOUNT (F-51). Drawing a
		// different *account* of the same customer would be a legitimate transfer, but a load run
		// made only of those would exercise none of the cross-customer path.
		other := customer
		for other == customer && p.spec.Size > 1 {
			other = random.IntN(p.spec.Size)
		}
		action.CounterpartyRef = accountRef(other, random.IntN(p.spec.AccountsPerCustomer), p.spec.AccountsPerCustomer)
		action.TransferRef = transferRef(date, seq)
		action.MovementRefs = [2]string{
			movementRef(action.TransferRef, 1),
			movementRef(action.TransferRef, 2),
		}
		currency := pick(random, block.Currencies)
		action.Amount = money.Amount{
			Minor:    drawMinor(random, block.Amount),
			Currency: currency,
		}
	}

	return action
}

func (p Population) pickCohort(random *rand.Rand) cohortBlock {
	// Weighted by events rather than by headcount. A corporate customer generating 36 events a day
	// contributes more than twice what a retail one does, and drawing by share alone would
	// understate the heaviest traffic in the estate.
	target := random.Float64() * p.eventTotal
	running := 0.0
	for _, block := range p.blocks {
		running += block.eventWeight
		if target < running {
			return block
		}
	}
	return p.blocks[len(p.blocks)-1]
}

func pick[T any](random *rand.Rand, weighted []Weighted[T]) T {
	total := 0.0
	for _, entry := range weighted {
		total += entry.Weight
	}
	target := random.Float64() * total
	running := 0.0
	for _, entry := range weighted {
		running += entry.Weight
		if target < running {
			return entry.Value
		}
	}
	return weighted[len(weighted)-1].Value
}

// drawMinor is the only place a float becomes money, and it hands back an int64.
//
// Log-normal: median * exp(sigma * z), with z standard normal by Box-Muller. rand.NormFloat64 would
// be shorter and is a ziggurat whose tables are an implementation detail; Box-Muller over Float64 is
// a specified function of a specified generator, which is what "byte-identical from the manifest"
// requires. The same reasoning as the exponential draw in internal/arrivals.
func drawMinor(random *rand.Rand, spec AmountSpec) int64 {
	// Float64 is [0,1); u1 must not be 0 or the logarithm diverges.
	u1 := 1 - random.Float64()
	u2 := random.Float64()
	z := math.Sqrt(-2*math.Log(u1)) * math.Cos(2*math.Pi*u2)

	drawn := float64(spec.MedianMinor) * math.Exp(spec.Sigma*z)
	if drawn >= float64(spec.MaxMinor) {
		return spec.MaxMinor
	}
	// Rounding half up, written out rather than reached for: math.Round is on the forbidden list in
	// internal/money/source_test.go precisely so that this conversion has to be looked at. Every
	// amount here is positive, which is what makes +0.5 correct.
	return money.Clamp(int64(drawn+0.5), spec.MinMinor, spec.MaxMinor)
}

// customerRef renders CU followed by ten digits, per CustomerRef in the ledger's OpenAPI document.
func customerRef(index int) string { return fmt.Sprintf("CU%010d", index) }

// accountRef renders TB followed by fourteen characters of [0-9A-Z], per AccountRef.
//
// Base 36 rather than decimal, because the pattern permits letters and a generator that emitted
// only digits would never produce a reference exercising the other half of the character class.
func accountRef(customer, slot, perCustomer int) string {
	const digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
	n := customer*perCustomer + slot
	out := []byte("00000000000000")
	for i := len(out) - 1; i >= 0 && n > 0; i-- {
		out[i] = digits[n%36]
		n /= 36
	}
	return "TB" + string(out)
}

// transferRef renders TB, CCYYMMDD, then a ten-digit sequence - the format the contract's own
// description states, rather than one invented to satisfy the pattern.
func transferRef(date bankday.Date, seq int64) string {
	return fmt.Sprintf("TB%s%010d", compactDate(date), seq%10_000_000_000)
}

// holdRef is the same shape under HL, per HoldRef.
func holdRef(date bankday.Date, seq int64) string {
	return fmt.Sprintf("HL%s%010d", compactDate(date), seq%10_000_000_000)
}

// movementRef is the transfer reference, a hyphen, then the two-digit leg number.
func movementRef(transfer string, leg int) string {
	return fmt.Sprintf("%s-%02d", transfer, leg)
}

func compactDate(date bankday.Date) string {
	iso := date.String() // YYYY-MM-DD
	return iso[0:4] + iso[5:7] + iso[8:10]
}
