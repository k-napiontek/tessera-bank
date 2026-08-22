package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/scenario"
)

const committedScenarios = "../../../contracts/workload/tessera-scenarios-v1.json"

func scenarioCatalogue(t *testing.T) scenario.Catalogue {
	t.Helper()
	document, err := os.ReadFile(committedScenarios)
	if err != nil {
		t.Fatalf("reading the committed scenario catalogue: %v", err)
	}
	decoded, err := scenario.Decode(document)
	if err != nil {
		t.Fatalf("decoding it: %v", err)
	}
	return decoded
}

// degradedManifest writes a run manifest that says it was executed under the named condition.
//
// The digest is computed from the committed catalogue rather than transcribed into a fixture, so a
// scenario edited tomorrow cannot leave this test agreeing with a catalogue that no longer exists.
func degradedManifest(t *testing.T, scenarioID string) string {
	t.Helper()
	base, err := os.ReadFile(filepath.Join("testdata", "run.json"))
	if err != nil {
		t.Fatalf("reading the base manifest: %v", err)
	}
	var record map[string]any
	if err := json.Unmarshal(base, &record); err != nil {
		t.Fatalf("unmarshalling it: %v", err)
	}
	record["hardware"] = "Darwin arm64, 10 cores, go1.25.6"
	record["scenarioId"] = scenarioID
	record["scenarioDigest"] = scenarioCatalogue(t).Digest()

	path := filepath.Join(t.TempDir(), "degraded.json")
	written, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		t.Fatalf("marshalling: %v", err)
	}
	if err := os.WriteFile(path, written, 0o600); err != nil {
		t.Fatalf("writing: %v", err)
	}
	return path
}

// scrapeWith is the committed closing scrape with one series replaced, which is how a condition
// shows up: everything the run did is unchanged and one line is over its objective's threshold.
func scrapeWith(t *testing.T, name, replacement string) string {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "after.prom"))
	if err != nil {
		t.Fatalf("reading the closing scrape: %v", err)
	}
	var kept []string
	for _, line := range strings.Split(string(body), "\n") {
		if strings.HasPrefix(line, name+"{") || strings.HasPrefix(line, name+" ") {
			continue
		}
		kept = append(kept, line)
	}
	kept = append(kept, replacement)

	path := filepath.Join(t.TempDir(), "after.prom")
	if err := os.WriteFile(path, []byte(strings.Join(kept, "\n")), 0o600); err != nil {
		t.Fatalf("writing: %v", err)
	}
	return path
}

func report(t *testing.T, args ...string) string {
	t.Helper()
	var out strings.Builder
	if err := run(args, &out); err != nil {
		t.Fatalf("generating the report: %v", err)
	}
	return out.String()
}

func degradedArgs(manifestPath, afterPath string) []string {
	return []string{
		"--manifest", manifestPath,
		"--catalogue", filepath.Join("..", "..", "..", "contracts", "slo", "tessera-slo-v1.json"),
		"--scenario", committedScenarios,
		"--before", filepath.Join("testdata", "before.prom"),
		"--after", afterPath,
		"--baseline-before", filepath.Join("testdata", "before.prom"),
		"--baseline-after", filepath.Join("testdata", "after.prom"),
	}
}

func TestADeclaredMoveThatHappenedIsReportedAsDeclared(t *testing.T) {
	// SCN-OUTBOX-STUCK declares that the outbox freshness objective moves and that money movement,
	// posting latency and gateway availability do not. This is that run: the lag ends at 240s
	// against a 60s target and nothing else in the scrape changes.
	page := report(t, degradedArgs(
		degradedManifest(t, "SCN-OUTBOX-STUCK"),
		scrapeWith(t, "ledger_outbox_lag_seconds", `ledger_outbox_lag_seconds{application="ledger-api",} 240.0`),
	)...)

	if !strings.Contains(page, "Signature") {
		t.Fatalf("no signature section:\n%s", page)
	}
	line := lineFor(t, page, "SLO-LEDGER-OUTBOX-FRESHNESS")
	if !strings.Contains(line, "move") || !strings.Contains(line, asDeclared) {
		t.Errorf("the declared move reads %q", line)
	}
	if !strings.Contains(line, "within threshold") || !strings.Contains(line, "outside threshold") {
		t.Errorf("the line does not show both standings: %q", line)
	}

	// The load-bearing half. Every flat objective has to be judged too, or the signature is the
	// half a write-up would have produced anyway.
	for _, flat := range []string{
		"SLO-LEDGER-MOVEMENT-SUCCESS", "SLO-LEDGER-POSTING-LATENCY", "SLO-GATEWAY-AVAILABILITY",
	} {
		flatLine := lineFor(t, page, flat)
		if !strings.Contains(flatLine, "flat") || !strings.Contains(flatLine, asDeclared) {
			t.Errorf("%s reads %q", flat, flatLine)
		}
	}
	if strings.Contains(page, contradicted) {
		t.Errorf("a run that behaved as declared reported a contradiction:\n%s", page)
	}
}

func TestAFlatObjectiveThatMovedIsContradicted(t *testing.T) {
	// The finding the section exists to produce. SCN-OUTBOX-STUCK says money movement is untouched
	// by a broker outage - if it were not, the outbox would not be doing its job, and this is the
	// run that would say so.
	page := report(t, degradedArgs(
		degradedManifest(t, "SCN-OUTBOX-STUCK"),
		scrapeWith(t, "ledger_transfers_total",
			`ledger_transfers_total{application="ledger-api",operation="transfer",outcome="failed",} 900.0`),
	)...)

	line := lineFor(t, page, "SLO-LEDGER-MOVEMENT-SUCCESS")
	if !strings.Contains(line, contradicted) {
		t.Errorf("an objective declared flat that missed its target reads %q", line)
	}
}

func TestADeclaredMoveThatDidNotHappenIsContradicted(t *testing.T) {
	// The other direction, and the one that keeps the catalogue honest: a condition that was
	// supposed to show up and did not is a claim about the estate that turned out to be wrong.
	page := report(t, degradedArgs(
		degradedManifest(t, "SCN-OUTBOX-STUCK"),
		filepath.Join("testdata", "after.prom"),
	)...)

	line := lineFor(t, page, "SLO-LEDGER-OUTBOX-FRESHNESS")
	if !strings.Contains(line, contradicted) {
		t.Errorf("a declared move that did not happen reads %q", line)
	}
}

func TestAConditionThatMovesNothingSaysSoAndStillAsserts(t *testing.T) {
	// Three of the seven move nothing this estate has an objective for. The section has to print the
	// reason and then judge the flat list anyway, because that list is the whole of the assertion.
	page := report(t, degradedArgs(
		degradedManifest(t, "SCN-LIMITER-STORM"),
		filepath.Join("testdata", "after.prom"),
	)...)

	if !strings.Contains(page, "declared to move nothing this estate has an objective for") {
		t.Errorf("the section does not say the condition moves nothing:\n%s", page)
	}
	if !strings.Contains(page, "tessera_gateway_refusals_total") {
		t.Errorf("the section does not carry the reason from the catalogue:\n%s", page)
	}
	line := lineFor(t, page, "SLO-GATEWAY-AVAILABILITY")
	if !strings.Contains(line, "flat") || !strings.Contains(line, asDeclared) {
		t.Errorf("the flat list was not judged: %q", line)
	}
}

func TestTheReportRefusesACatalogueTheRunWasNotExecutedUnder(t *testing.T) {
	// A signature judged against a catalogue somebody edited between the run and the report is a
	// judgement against a declaration that was never made.
	path := degradedManifest(t, "SCN-OUTBOX-STUCK")
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("reading: %v", err)
	}
	var record map[string]any
	if err := json.Unmarshal(body, &record); err != nil {
		t.Fatalf("unmarshalling: %v", err)
	}
	record["scenarioDigest"] = "0000000000000000000000000000000000000000000000000000000000000000"
	rewritten, _ := json.Marshal(record)
	if err := os.WriteFile(path, rewritten, 0o600); err != nil {
		t.Fatalf("writing: %v", err)
	}

	var out strings.Builder
	err = run(degradedArgs(path, filepath.Join("testdata", "after.prom")), &out)
	if err == nil {
		t.Fatal("reported a signature against a catalogue the run did not use")
	}
	if !strings.Contains(err.Error(), "different catalogue") {
		t.Errorf("the error %q does not say what is wrong", err)
	}
}

func TestASignatureNeedsTheNormalItDegradedFrom(t *testing.T) {
	// "A degradation described without the normal it degraded from is an anecdote" - WP-24's own
	// Constraint, enforced rather than quoted.
	var out strings.Builder
	err := run([]string{
		"--manifest", degradedManifest(t, "SCN-OUTBOX-STUCK"),
		"--catalogue", filepath.Join("..", "..", "..", "contracts", "slo", "tessera-slo-v1.json"),
		"--scenario", committedScenarios,
		"--before", filepath.Join("testdata", "before.prom"),
		"--after", filepath.Join("testdata", "after.prom"),
	}, &out)
	if err == nil {
		t.Fatal("judged a signature with no baseline to judge it against")
	}
	if !strings.Contains(err.Error(), "anecdote") {
		t.Errorf("the error %q does not say why", err)
	}
}

func TestADegradedRunCannotBeReportedAsAnUndegradedOne(t *testing.T) {
	// Without --scenario the report would print a perfectly ordinary run report for a run that was
	// deliberately broken, and nothing on the page would say so.
	var out strings.Builder
	err := run([]string{
		"--manifest", degradedManifest(t, "SCN-OUTBOX-STUCK"),
		"--catalogue", filepath.Join("..", "..", "..", "contracts", "slo", "tessera-slo-v1.json"),
		"--before", filepath.Join("testdata", "before.prom"),
		"--after", filepath.Join("testdata", "after.prom"),
	}, &out)
	if err == nil {
		t.Fatal("reported a degraded run as though nothing had been done to it")
	}
	if !strings.Contains(err.Error(), "SCN-OUTBOX-STUCK") {
		t.Errorf("the error %q does not name the condition", err)
	}
}

func TestTheSignatureIsByteIdenticalForTheSameInputs(t *testing.T) {
	args := degradedArgs(
		degradedManifest(t, "SCN-OUTBOX-STUCK"),
		scrapeWith(t, "ledger_outbox_lag_seconds", `ledger_outbox_lag_seconds{application="ledger-api",} 240.0`),
	)
	if first, second := report(t, args...), report(t, args...); first != second {
		t.Error("two runs over the same inputs produced two reports")
	}
}

func lineFor(t *testing.T, page, objectiveID string) string {
	t.Helper()
	section := page[strings.Index(page, "\nSignature\n"):]
	for _, line := range strings.Split(section, "\n") {
		// The table row, not a mention of the identifier in the prose above it.
		if strings.HasPrefix(strings.TrimSpace(line), objectiveID) {
			return line
		}
	}
	t.Fatalf("no signature line for %s in:\n%s", objectiveID, section)
	return ""
}
