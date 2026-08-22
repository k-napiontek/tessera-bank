package main

import (
	"bufio"
	"bytes"
	"strings"
	"testing"
)

const population = `{"kind":"population","from":"2026-03-02","to":"2026-03-02","baseCurrency":"PLN"}
{"kind":"open","customerRef":"CU0000000000","accountRef":"TB00000000000000","accountType":"LIABILITY"}
{"kind":"open","customerRef":"CU0000000001","accountRef":"TB00000000000001","accountType":"ASSET","treasury":true}
`

func render(t *testing.T, stream string) string {
	t.Helper()
	var rendered bytes.Buffer
	out := bufio.NewWriter(&rendered)

	head := &header{}
	customers := map[string]bool{}
	accounts := 0
	for _, line := range strings.Split(stream, "\n") {
		if err := emit(out, []byte(line), head, customers, &accounts, 0); err != nil {
			t.Fatalf("the stream could not be rendered: %v", err)
		}
	}
	if err := out.Flush(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	return rendered.String()
}

// The whole of WP-25d's first finding, pinned.
//
// legacy/customer-master's schema declares
//
//	CONSTRAINT account_movement_ck CHECK (last_movement_date IS NULL OR last_movement_date >= opened_date)
//
// and internal/client.Fund dates the opening credit the day *before* the run, which is the rule
// services/ledger-loader already follows in Header.openingDate: an opening balance is the position
// the day starts from rather than part of it. Opening a stratum-1 account on the business date makes
// those two correct rules jointly impossible - every funding posting is refused ORA-02290 by a 2011
// check constraint, and the adapter retries it for ever.
//
// The account is therefore opened on the day the opening balance is dated, which is what both other
// consumers of this stream already do.
func TestAnAccountIsOpenedOnTheDayItsOpeningBalanceIsDated(t *testing.T) {
	rendered := render(t, population)

	if !strings.Contains(rendered, "opened_date) VALUES ('TB00000000000000', 'CU0000000000', 'LIABILITY', 'PLN', 'OPEN', 100000000, DATE '2026-03-01')") {
		t.Errorf("an account was not opened on the stream's opening date:\n%s", rendered)
	}
	if strings.Contains(rendered, "DATE '2026-03-02');") {
		t.Errorf("something is still dated the business date rather than the opening date:\n%s", rendered)
	}
}

// A customer cannot be onboarded after the account it owns was opened either - onboarded_date is
// what the 2011 core records, and an account opened before its holder existed is the same class of
// impossibility one column over.
func TestACustomerIsOnboardedNoLaterThanItsAccounts(t *testing.T) {
	rendered := render(t, population)
	if !strings.Contains(rendered, "onboarded_date) VALUES ('CU0000000000', 'SYNTHETIC', 'SYNTHETIC', DATE '1970-01-01', 'SYN-1', DATE '2026-03-01')") {
		t.Errorf("the customer is not onboarded on the opening date:\n%s", rendered)
	}
}

// The treasury carries one leg of every funding and opens at zero, which is unchanged - it is the
// counterparty rather than a funded account.
func TestTheTreasuryOpensAtZero(t *testing.T) {
	rendered := render(t, population)
	if !strings.Contains(rendered, "'TB00000000000001', 'CU0000000001', 'ASSET', 'PLN', 'OPEN', 0,") {
		t.Errorf("the treasury does not open at zero:\n%s", rendered)
	}
}

func TestAStreamWithNoHeaderIsStillRenderableButDatesNothingWrongly(t *testing.T) {
	// A header-less stream would silently render DATE '' and fail inside sqlplus with a message
	// about a date format, three steps from the cause.
	var rendered bytes.Buffer
	out := bufio.NewWriter(&rendered)
	head := &header{}
	customers := map[string]bool{}
	accounts := 0

	err := emit(out, []byte(`{"kind":"open","customerRef":"CU1","accountRef":"TB1","accountType":"ASSET"}`),
		head, customers, &accounts, 0)
	if err == nil {
		t.Fatal("an account before the population header was rendered rather than refused")
	}
}
