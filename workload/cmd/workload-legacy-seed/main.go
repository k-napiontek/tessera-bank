// Command workload-legacy-seed renders a WP-20 population as Oracle INSERT statements on stdout.
//
// It drives nothing and connects to nothing - it writes SQL, and workload/scripts/legacy-up.sh pipes
// it into sqlplus inside the Oracle container. The workload module holds no database connection and
// internal/purity forbids database/sql outright, which is the same boundary WP-22's loader was built
// on the other side of.
//
//	go -C workload run ./cmd/workload-dataset --model ../contracts/workload/tessera-day-v1.json \
//	  --from 2026-03-02 --to 2026-03-02 --seed 42 --scale 0.0002 --customers 2000 \
//	  | go -C workload run ./cmd/workload-legacy-seed
//
// # Why the identity columns carry a marker rather than a manufactured person
//
// `legacy/customer-master`'s CUSTOMER table declares family_name, given_name, date_of_birth and
// national_id NOT NULL, because a 2011 core built around customers has nowhere to put a customer
// without them. The only generator in this repository that fills them, `SyntheticData`, lives in
// **test** scope precisely so that code manufacturing personal data is not inside a deployable
// artefact - the traceability matrix records that as how REQ-DP-001 is met at that tier.
//
// A load fixture is not a deployable artefact either, but it is also not the place to grow a second
// generator of plausible names. So the columns are filled with a constant that could not be anyone:
// every customer is SYNTHETIC SYNTHETIC, born on the same date, with a national identifier of the
// form SYN-<ordinal>. There is nothing to anonymise because there was never an identity - which is a
// stronger position than a well-anonymised one, and it is the whole of what this fixture needs,
// since every operation it drives is keyed by reference.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
)

// The constant that fills the columns a 2011 core will not accept as null. Not a name: a marker.
const (
	markerName     = "SYNTHETIC"
	markerBirth    = "1970-01-01"
	markerIDPrefix = "SYN-"
)

// openingBalance is what every seeded account holds, in minor units. The SOAP driver reads accounts
// and notifies postings; it never spends a balance down, so one figure large enough to be plausible
// is all this needs. NUMBER(15,0) is the column, so this is comfortably inside it.
const openingBalance = 1_000_000_00

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "workload-legacy-seed: %v\n", err)
		os.Exit(1)
	}
}

type header struct {
	Kind         string `json:"kind"`
	From         string `json:"from"`
	BaseCurrency string `json:"baseCurrency"`
}

type open struct {
	Kind        string `json:"kind"`
	CustomerRef string `json:"customerRef"`
	AccountRef  string `json:"accountRef"`
	AccountType string `json:"accountType"`
	Treasury    bool   `json:"treasury"`
}

func run() error {
	var limit int
	flag.IntVar(&limit, "accounts", 0, "stop after this many accounts; 0 takes the whole stream")
	flag.Parse()

	in := bufio.NewReaderSize(os.Stdin, 1<<20)
	out := bufio.NewWriterSize(os.Stdout, 1<<20)
	defer func() { _ = out.Flush() }()

	var head header
	customers := map[string]bool{}
	accounts := 0

	preamble(out)

	for {
		line, err := in.ReadBytes('\n')
		if len(line) > 0 {
			if problem := emit(out, line, &head, customers, &accounts, limit); problem != nil {
				return problem
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
	}

	if head.Kind == "" {
		return fmt.Errorf("the stream carries no population header")
	}

	fmt.Fprintf(out, "COMMIT;\n")
	fmt.Fprintf(out, "PROMPT seeded %d customers and %d accounts\n", len(customers), accounts)
	fmt.Fprintf(out, "EXIT\n")
	return nil
}

func emit(out *bufio.Writer, line []byte, head *header, customers map[string]bool, accounts *int, limit int) error {
	trimmed := strings.TrimSpace(string(line))
	if trimmed == "" {
		return nil
	}
	var kind struct {
		Kind string `json:"kind"`
	}
	if err := json.Unmarshal([]byte(trimmed), &kind); err != nil {
		return fmt.Errorf("the stream carries a line that is not JSON: %w", err)
	}

	switch kind.Kind {
	case "population":
		return json.Unmarshal([]byte(trimmed), head)
	case "open":
		// Past the limit the rest of the stream is read and dropped rather than the reader closing.
		// Closing stdin early hands the upstream a broken pipe, and under `set -o pipefail` that is
		// a failed run reported as a rendering error - which is a fixture describing itself.
		if limit > 0 && *accounts >= limit {
			return nil
		}
		var record open
		if err := json.Unmarshal([]byte(trimmed), &record); err != nil {
			return err
		}
		if !customers[record.CustomerRef] {
			customers[record.CustomerRef] = true
			writeCustomer(out, record.CustomerRef, len(customers), head.From)
		}
		writeAccount(out, record, head)
		*accounts++
	}
	return nil
}

func preamble(out *bufio.Writer) {
	// Errors stop the seed rather than being reported at the end over a partly loaded schema, which
	// is the shape of problem that produces a run measuring a fraction of the population it names.
	fmt.Fprintln(out, "WHENEVER SQLERROR EXIT FAILURE")
	fmt.Fprintln(out, "SET DEFINE OFF")
	fmt.Fprintln(out, "SET FEEDBACK OFF")
}

func writeCustomer(out *bufio.Writer, ref string, ordinal int, onboarded string) {
	fmt.Fprintf(out,
		"INSERT INTO customer (customer_ref, family_name, given_name, date_of_birth, national_id, onboarded_date) "+
			"VALUES ('%s', '%s', '%s', DATE '%s', '%s%d', DATE '%s');\n",
		ref, markerName, markerName, markerBirth, markerIDPrefix, ordinal, onboarded)
}

func writeAccount(out *bufio.Writer, record open, head *header) {
	balance := openingBalance
	if record.Treasury {
		balance = 0
	}
	fmt.Fprintf(out,
		"INSERT INTO account (account_ref, customer_ref, account_type, currency, status, booked_balance, opened_date) "+
			"VALUES ('%s', '%s', '%s', '%s', 'OPEN', %d, DATE '%s');\n",
		record.AccountRef, record.CustomerRef, record.AccountType, head.BaseCurrency, balance, head.From)
}
