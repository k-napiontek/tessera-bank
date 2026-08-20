// Package money carries an amount as int64 minor units beside its ISO 4217 alphabetic code.
//
// Money is never a floating-point number. That rule is stated in CLAUDE.md, enforced against the
// source in edge/web-banking (money.source.test.ts) and in batch/reporting (money.py), and enforced
// here by source_test.go in the third language where it matters. Go makes the mistake easy in one
// particular way: an untyped constant like 1.5 is a float64 the moment it meets an int64, and
// strconv.ParseFloat is one keystroke from strconv.ParseInt.
//
// There is no scale in this package and no decimal point anywhere in it. The canonical data model
// resolves scale per currency; a type that rendered 12000 as "120.00" would have to divide to do
// it, and a division is exactly what must not appear on an amount path.
package money

import (
	"errors"
	"strconv"
)

// The int64 range, written as literals so that this package imports no math at all. A packed
// decimal PIC S9(13)V99 tops out far below either bound; these are the language's limits, and what
// Add refuses to cross.
const (
	maxMinor int64 = 9223372036854775807
	minMinor int64 = -9223372036854775808
)

var (
	// ErrCurrency reports a code this estate cannot carry from the edge to the mainframe.
	ErrCurrency = errors.New("money: not a currency this estate carries")
	// ErrCurrencyMismatch reports arithmetic across two currencies, which has no answer.
	ErrCurrencyMismatch = errors.New("money: currencies differ")
	// ErrOverflow reports a sum that does not fit in an int64. Refused rather than wrapped.
	ErrOverflow = errors.New("money: amount overflows int64")
)

// Currency is an ISO 4217 alphabetic code.
type Currency string

// carriable is transcribed rather than read from contracts/workload/tessera-day-v1.json, because a
// list read out of the document it is checking agrees with every version of that document.
//
// Every entry is scale 2. Stratum 0 stores an amount as PIC S9(13)V99 COMP-3 and cannot represent
// JPY (scale 0) or BHD (scale 3), so the integration tier is required to reject those before they
// reach the mainframe - see docs/architecture/canonical-data-model.md section 2, and F-09. A
// workload model naming one would be describing a rejection rather than a day, which is WP-24's
// subject and not this one's.
var carriable = []Currency{"CHF", "EUR", "GBP", "PLN", "USD"}

// Carriable lists the currencies this estate can carry end to end, in a stable order.
func Carriable() []Currency {
	out := make([]Currency, len(carriable))
	copy(out, carriable)
	return out
}

// Valid reports whether a code is one this estate carries.
func Valid(currency Currency) bool {
	for _, known := range carriable {
		if known == currency {
			return true
		}
	}
	return false
}

// Amount is a quantity of money: minor units, and the currency that says what a minor unit is.
type Amount struct {
	Minor    int64
	Currency Currency
}

// New builds an Amount, refusing a currency this estate cannot carry.
func New(minor int64, currency Currency) (Amount, error) {
	if !Valid(currency) {
		return Amount{}, ErrCurrency
	}
	return Amount{Minor: minor, Currency: currency}, nil
}

// Add returns the sum, refusing a currency mismatch and refusing overflow.
//
// Overflow is refused rather than wrapped for the reason WP-06's Money gives three strata away: a
// wrapped total is a plausible-looking figure that is simply wrong, and this repository's own trap
// list is a list of exactly those.
func (a Amount) Add(b Amount) (Amount, error) {
	if a.Currency != b.Currency {
		return Amount{}, ErrCurrencyMismatch
	}
	if b.Minor > 0 && a.Minor > maxMinor-b.Minor {
		return Amount{}, ErrOverflow
	}
	if b.Minor < 0 && a.Minor < minMinor-b.Minor {
		return Amount{}, ErrOverflow
	}
	return Amount{Minor: a.Minor + b.Minor, Currency: a.Currency}, nil
}

// IsPositive reports whether the amount is strictly above zero.
func (a Amount) IsPositive() bool { return a.Minor > 0 }

// String renders minor units and the code, with no decimal point. See the package comment.
func (a Amount) String() string {
	return strconv.FormatInt(a.Minor, 10) + " " + string(a.Currency)
}

// Clamp holds a figure of minor units inside a declared range. The amount distributions in the
// workload model each declare one, so that a heavy tail cannot produce a transfer the estate would
// refuse for reasons that have nothing to do with load.
func Clamp(minor, low, high int64) int64 {
	if minor < low {
		return low
	}
	if minor > high {
		return high
	}
	return minor
}
