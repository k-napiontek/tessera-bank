package scenario_test

import (
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/k-napiontek/tessera-bank/workload/internal/scenario"
)

// The committed catalogue, read once. Every test that needs a valid document starts from this
// rather than from a hand-written fixture, so a test cannot pass against a shape the contract no
// longer has.
const committed = "../../../contracts/workload/tessera-scenarios-v1.json"

func read(t *testing.T) []byte {
	t.Helper()
	document, err := os.ReadFile(committed)
	if err != nil {
		t.Fatalf("reading the committed catalogue: %v", err)
	}
	return document
}

// mutate decodes the committed catalogue into a map, hands it to change, and re-encodes it. Working
// through a map rather than through the struct is the point: a struct cannot express a document the
// decoder is supposed to refuse.
func mutate(t *testing.T, change func(document map[string]any)) []byte {
	t.Helper()
	var document map[string]any
	if err := json.Unmarshal(read(t), &document); err != nil {
		t.Fatalf("unmarshalling the committed catalogue: %v", err)
	}
	change(document)
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatalf("marshalling the mutated catalogue: %v", err)
	}
	return encoded
}

func first(t *testing.T, document map[string]any) map[string]any {
	t.Helper()
	scenarios, ok := document["scenarios"].([]any)
	if !ok || len(scenarios) == 0 {
		t.Fatalf("the catalogue has no scenarios to mutate")
	}
	entry, ok := scenarios[0].(map[string]any)
	if !ok {
		t.Fatalf("the first scenario is not an object")
	}
	return entry
}

func TestTheCommittedCatalogueDecodes(t *testing.T) {
	catalogue, err := scenario.Decode(read(t))
	if err != nil {
		t.Fatalf("the committed catalogue does not decode: %v", err)
	}
	if catalogue.CatalogueID != scenario.FormatID {
		t.Errorf("catalogueId is %q, want %q", catalogue.CatalogueID, scenario.FormatID)
	}
	if len(catalogue.Scenarios) == 0 {
		t.Fatal("the committed catalogue holds no scenarios")
	}
	if catalogue.Digest() == "" {
		t.Error("a decoded catalogue has no digest, so no manifest can record which one it was")
	}
}

// The half that matters, and the reason this package exists beside contracts/check-workload-scenarios.py
// rather than instead of it: the checker proves the committed document is coherent before it is
// committed, and this proves the loader read it the same way - against whatever document a run is
// pointed at, not only against the one that went through validate.sh.
func TestDecodeRefusesADocumentItDoesNotFullyUnderstand(t *testing.T) {
	cases := []struct {
		name     string
		document []byte
		wants    string
	}{
		{
			name: "a catalogue format this engine does not read",
			document: mutate(t, func(document map[string]any) {
				document["catalogueId"] = "TB-SCENARIOS-V2"
			}),
			wants: "catalogueId",
		},
		{
			name: "a field nobody declared",
			document: mutate(t, func(document map[string]any) {
				first(t, document)["blastRadius"] = "everything"
			}),
			wants: "blastRadius",
		},
		{
			name: "a condition the injector cannot dispatch on",
			document: mutate(t, func(document map[string]any) {
				first(t, document)["condition"] = "disk-full"
			}),
			wants: "disk-full",
		},
		{
			name: "an objective expected both to move and to stay flat",
			document: mutate(t, func(document map[string]any) {
				entry := first(t, document)
				entry["flatObjectives"] = entry["movesObjectives"]
			}),
			wants: "both",
		},
		{
			name: "a scenario that moves nothing and does not say why",
			document: mutate(t, func(document map[string]any) {
				entry := first(t, document)
				entry["movesObjectives"] = []any{}
				delete(entry, "movesNothingBecause")
			}),
			wants: "why",
		},
		{
			name: "a required parameter left unset",
			document: mutate(t, func(document map[string]any) {
				entry := first(t, document)
				entry["condition"] = "pool-exhaustion"
				entry["parameters"] = map[string]any{"lockTable": "balance"}
			}),
			wants: "lockMode",
		},
		{
			name: "a parameter belonging to another condition",
			document: mutate(t, func(document map[string]any) {
				entry := first(t, document)
				entry["condition"] = "stuck-outbox"
				entry["parameters"] = map[string]any{"skewMinutes": 30}
			}),
			wants: "skewMinutes",
		},
		{
			name: "a condition still held after the day has ended",
			document: mutate(t, func(document map[string]any) {
				entry := first(t, document)
				entry["startsAtMinute"] = 1400
				entry["holdsForMinutes"] = 120
			}),
			wants: "business day",
		},
		{
			name: "two scenarios under one identifier",
			document: mutate(t, func(document map[string]any) {
				scenarios := document["scenarios"].([]any)
				if len(scenarios) < 2 {
					t.Skip("the catalogue holds one scenario, so it cannot hold a duplicate")
				}
				second := scenarios[1].(map[string]any)
				second["scenarioId"] = first(t, document)["scenarioId"]
			}),
			wants: "twice",
		},
	}

	for _, each := range cases {
		t.Run(each.name, func(t *testing.T) {
			_, err := scenario.Decode(each.document)
			if err == nil {
				t.Fatalf("decoded a document that should have been refused")
			}
			if !strings.Contains(err.Error(), each.wants) {
				t.Errorf("error %q does not name %q", err, each.wants)
			}
		})
	}
}

func TestTheDigestFollowsWhatTheCatalogueSaysAndNotHowItIsWritten(t *testing.T) {
	original, err := scenario.Decode(read(t))
	if err != nil {
		t.Fatalf("decoding: %v", err)
	}

	var document map[string]any
	if err := json.Unmarshal(read(t), &document); err != nil {
		t.Fatalf("unmarshalling: %v", err)
	}
	reindented, err := json.MarshalIndent(document, "", "    ")
	if err != nil {
		t.Fatalf("re-indenting: %v", err)
	}
	same, err := scenario.Decode(reindented)
	if err != nil {
		t.Fatalf("decoding the re-indented catalogue: %v", err)
	}
	if same.Digest() != original.Digest() {
		t.Errorf("re-indenting changed the digest: %s then %s", original.Digest(), same.Digest())
	}

	changed, err := scenario.Decode(mutate(t, func(document map[string]any) {
		first(t, document)["holdsForMinutes"] = 17
	}))
	if err != nil {
		t.Fatalf("decoding the changed catalogue: %v", err)
	}
	if changed.Digest() == original.Digest() {
		t.Error("changing how long a condition is held left the digest alone")
	}
}

func TestFindNamesTheScenarioOrSaysWhatThereIs(t *testing.T) {
	catalogue, err := scenario.Decode(read(t))
	if err != nil {
		t.Fatalf("decoding: %v", err)
	}
	wanted := catalogue.Scenarios[0].ScenarioID
	found, err := catalogue.Find(wanted)
	if err != nil {
		t.Fatalf("Find(%q): %v", wanted, err)
	}
	if found.ScenarioID != wanted {
		t.Errorf("Find(%q) returned %q", wanted, found.ScenarioID)
	}

	_, err = catalogue.Find("SCN-NOT-A-SCENARIO")
	if err == nil {
		t.Fatal("Find accepted an identifier the catalogue does not hold")
	}
	// A run that names a scenario nobody wrote should be told which ones exist, because the failure
	// happens at the start of an expensive run and the answer is one line away.
	if !strings.Contains(err.Error(), wanted) {
		t.Errorf("error %q does not list what the catalogue does hold", err)
	}
}

func TestEveryScenarioNamesAWindowTheDayCanHold(t *testing.T) {
	catalogue, err := scenario.Decode(read(t))
	if err != nil {
		t.Fatalf("decoding: %v", err)
	}
	for _, each := range catalogue.Scenarios {
		if each.HoldsForMinutes <= 0 {
			t.Errorf("%s: held for %d minutes", each.ScenarioID, each.HoldsForMinutes)
		}
		if int(each.EndsAtMinute()) <= int(each.StartsAtMinute) {
			t.Errorf("%s: ends at %s, having started at %s",
				each.ScenarioID, each.EndsAtMinute(), each.StartsAtMinute)
		}
	}
}
