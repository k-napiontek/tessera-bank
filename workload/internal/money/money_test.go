package money

import (
	"errors"
	"testing"
)

func TestNewRejectsACurrencyThisEstateCannotCarry(t *testing.T) {
	// Stratum 0 stores an amount as PIC S9(13)V99 COMP-3, which is scale 2. JPY is scale 0 and BHD
	// is scale 3, so a model naming either describes demand the integration tier is required to
	// refuse before it reaches the mainframe. See canonical-data-model.md section 2 and F-09.
	for _, code := range []string{"JPY", "BHD", "pln", "PL", "PLNN", ""} {
		if _, err := New(1, Currency(code)); !errors.Is(err, ErrCurrency) {
			t.Errorf("New(1, %q) = %v, want ErrCurrency", code, err)
		}
	}
}

func TestNewAcceptsEveryCarriableCurrency(t *testing.T) {
	for _, code := range Carriable() {
		if _, err := New(0, code); err != nil {
			t.Errorf("New(0, %q) = %v, want no error", code, err)
		}
	}
}

func TestAddRefusesAMixOfCurrencies(t *testing.T) {
	// The report equivalent of this is already refused a stratum away: WP-05's end-of-day report
	// prints no cross-currency total, because 100 PLN plus 100 EUR is a figure that means nothing.
	pln, _ := New(100, "PLN")
	eur, _ := New(100, "EUR")
	if _, err := pln.Add(eur); !errors.Is(err, ErrCurrencyMismatch) {
		t.Fatalf("100 PLN + 100 EUR = %v, want ErrCurrencyMismatch", err)
	}
}

func TestAddRefusesOverflowRatherThanWrapping(t *testing.T) {
	// WP-06's Money throws on overflow rather than wrapping, and this is the same decision in the
	// third language. A wrapped total is a plausible-looking figure that is simply wrong, which is
	// the failure mode this estate cares about most.
	top, _ := New(maxMinor, "PLN")
	one, _ := New(1, "PLN")
	if _, err := top.Add(one); !errors.Is(err, ErrOverflow) {
		t.Fatalf("maxMinor + 1 = %v, want ErrOverflow", err)
	}
	bottom, _ := New(minMinor, "PLN")
	minusOne, _ := New(-1, "PLN")
	if _, err := bottom.Add(minusOne); !errors.Is(err, ErrOverflow) {
		t.Fatalf("minMinor - 1 = %v, want ErrOverflow", err)
	}
}

func TestAddIsExactAcrossTheWholeRange(t *testing.T) {
	// A float64 has 53 bits of mantissa, so it loses whole minor units above about 9e15. These
	// three sums are exact in int64 and wrong in float64, which is the entire reason for the type.
	cases := []struct {
		a, b, want int64
	}{
		{1, 2, 3},
		{-100, 100, 0},
		{9007199254740993, 1, 9007199254740994},
		{maxMinor - 1, 1, maxMinor},
	}
	for _, c := range cases {
		a, _ := New(c.a, "PLN")
		b, _ := New(c.b, "PLN")
		sum, err := a.Add(b)
		if err != nil {
			t.Fatalf("%d + %d: %v", c.a, c.b, err)
		}
		if sum.Minor != c.want {
			t.Errorf("%d + %d = %d, want %d", c.a, c.b, sum.Minor, c.want)
		}
	}
}

func TestStringRendersMinorUnitsAndNeverADecimalPoint(t *testing.T) {
	// There is no scale here on purpose: the canonical data model resolves scale per currency, and
	// a type that formatted 12000 as "120.00" would have to divide to do it.
	amount, _ := New(12000, "PLN")
	if got := amount.String(); got != "12000 PLN" {
		t.Errorf("String() = %q, want %q", got, "12000 PLN")
	}
	negative, _ := New(-5, "EUR")
	if got := negative.String(); got != "-5 EUR" {
		t.Errorf("String() = %q, want %q", got, "-5 EUR")
	}
}

func TestClampHoldsAnAmountInsideADeclaredRange(t *testing.T) {
	if got := Clamp(50, 100, 900); got != 100 {
		t.Errorf("Clamp(50, 100, 900) = %d, want 100", got)
	}
	if got := Clamp(5000, 100, 900); got != 900 {
		t.Errorf("Clamp(5000, 100, 900) = %d, want 900", got)
	}
	if got := Clamp(500, 100, 900); got != 500 {
		t.Errorf("Clamp(500, 100, 900) = %d, want 500", got)
	}
}
