// Package scenario decodes the failure-scenario catalogue and turns it into the injector's types.
//
// It performs no I/O, for the reason internal/model performs none: Decode takes bytes, because the
// things that load a catalogue load it from different places, and because a package that opens
// files is a package the purity test has to argue with.
//
// A scenario is a separate contract from the day model beside it. The day model states demand and a
// scenario states degradation; every run manifest records the day model's digest so that two runs
// can be compared, so a model that changed because somebody added a fault would be a baseline
// nothing could be diffed against. ADR 0017 records that, and it is why this package exists rather
// than a few more fields on internal/model.
//
// It repeats several of contracts/check-workload-scenarios.py's checks, and that duplication is
// deliberate in exactly the way internal/model's is. The checker proves the committed document is
// coherent before it is committed; this proves the loader read it the same way, and it runs against
// whatever catalogue a run is pointed at rather than only against the one that went through
// validate.sh. What it does not repeat is the cross-reference into contracts/slo/: resolving an
// objective identifier needs the SLO catalogue, which the report has and the engine does not.
package scenario

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
)

// FormatID is the only scenario catalogue format this engine understands.
const FormatID = "TB-SCENARIOS-V1"

// Condition is the kind of degradation, which the injector dispatches on. It is a closed set here
// as well as in the schema: a condition nothing can produce is a scenario nobody can run, and
// finding that out at decode time costs a second rather than a whole run.
type Condition string

const (
	SlowDependency Condition = "slow-dependency"
	PartialOutage  Condition = "partial-outage"
	StuckOutbox    Condition = "stuck-outbox"
	ConsumerLag    Condition = "consumer-lag"
	RateLimitStorm Condition = "rate-limit-storm"
	PoolExhaustion Condition = "pool-exhaustion"
	ClockSkew      Condition = "clock-skew"
)

// Parameters holds the union of every knob any condition takes, which is how the contract declares
// it: the shared JSON Schema subset has no oneOf, so the schema lists all of them as optional and
// the per-condition rules live beside the document rather than inside it. required names which
// fields each condition must have set, and a field belonging to another condition is refused rather
// than ignored - the injector would never read it, so the scenario would read as though a dial had
// been set that nothing looked at.
type Parameters struct {
	ExtraLatencyMillis int    `json:"extraLatencyMillis,omitempty"`
	RequestsPerSecond  int    `json:"requestsPerSecond,omitempty"`
	Subjects           int    `json:"subjects,omitempty"`
	LockMode           string `json:"lockMode,omitempty"`
	LockTable          string `json:"lockTable,omitempty"`
	SkewMinutes        int    `json:"skewMinutes,omitempty"`
}

// set names the parameters this value actually carries, so that "belongs to another condition" is
// answered from the document rather than from a guess about zero values.
func (p Parameters) set() map[string]bool {
	present := map[string]bool{}
	if p.ExtraLatencyMillis != 0 {
		present["extraLatencyMillis"] = true
	}
	if p.RequestsPerSecond != 0 {
		present["requestsPerSecond"] = true
	}
	if p.Subjects != 0 {
		present["subjects"] = true
	}
	if p.LockMode != "" {
		present["lockMode"] = true
	}
	if p.LockTable != "" {
		present["lockTable"] = true
	}
	if p.SkewMinutes != 0 {
		present["skewMinutes"] = true
	}
	return present
}

// required is the same table contracts/check-workload-scenarios.py holds, in the language that
// dispatches on it. Two copies of one statement, and both have to be extended together - which the
// unknown-condition refusal below makes noisy rather than silent.
var required = map[Condition][]string{
	SlowDependency: {"extraLatencyMillis"},
	PartialOutage:  {},
	StuckOutbox:    {},
	ConsumerLag:    {},
	RateLimitStorm: {"requestsPerSecond", "subjects"},
	PoolExhaustion: {"lockMode", "lockTable"},
	ClockSkew:      {"skewMinutes"},
}

// Scenario is one declared condition: what it degrades, how the fixture produces it, when in the
// business day, and the two lists that make its signature falsifiable.
type Scenario struct {
	ScenarioID          string         `json:"scenarioId"`
	Title               string         `json:"title"`
	Condition           Condition      `json:"condition"`
	Target              string         `json:"target"`
	Mechanism           string         `json:"mechanism"`
	StartsAtMinute      bankday.Minute `json:"startsAtMinute"`
	HoldsForMinutes     int            `json:"holdsForMinutes"`
	Parameters          Parameters     `json:"parameters"`
	MovesObjectives     []string       `json:"movesObjectives"`
	MovesNothingBecause string         `json:"movesNothingBecause,omitempty"`
	FlatObjectives      []string       `json:"flatObjectives"`
	IntroducedBy        string         `json:"introducedBy"`
	Rationale           string         `json:"rationale"`
}

// EndsAtMinute is the minute of the business day the condition is reverted at.
func (s Scenario) EndsAtMinute() bankday.Minute {
	return s.StartsAtMinute + bankday.Minute(s.HoldsForMinutes)
}

// Catalogue is the committed set.
type Catalogue struct {
	CatalogueID      string     `json:"catalogueId"`
	CatalogueVersion string     `json:"catalogueVersion"`
	Summary          string     `json:"summary"`
	Scenarios        []Scenario `json:"scenarios"`

	digest string
}

// Decode reads a scenario catalogue and refuses one it does not fully understand.
//
// Unknown fields are an error rather than something to ignore, for the reason internal/model gives:
// a mistyped field would otherwise load at its zero value, the condition would be injected with a
// dial nobody set, and the signature would describe something nobody asked for.
func Decode(document []byte) (Catalogue, error) {
	decoder := json.NewDecoder(bytes.NewReader(document))
	decoder.DisallowUnknownFields()

	var loaded Catalogue
	if err := decoder.Decode(&loaded); err != nil {
		return Catalogue{}, fmt.Errorf("scenario: %w", err)
	}
	if loaded.CatalogueID != FormatID {
		return Catalogue{}, fmt.Errorf("scenario: catalogueId is %q, and this engine reads %s only",
			loaded.CatalogueID, FormatID)
	}
	if err := loaded.validate(); err != nil {
		return Catalogue{}, err
	}

	digest, err := loaded.computeDigest()
	if err != nil {
		return Catalogue{}, err
	}
	loaded.digest = digest
	return loaded, nil
}

func (c Catalogue) validate() error {
	if len(c.Scenarios) == 0 {
		return fmt.Errorf("scenario: the catalogue declares no scenarios")
	}
	seen := map[string]bool{}
	for _, each := range c.Scenarios {
		if seen[each.ScenarioID] {
			return fmt.Errorf("scenario: %s is declared twice, and a signature capture is filed "+
				"under the identifier", each.ScenarioID)
		}
		seen[each.ScenarioID] = true
		if err := each.validate(); err != nil {
			return err
		}
	}
	return nil
}

func (s Scenario) validate() error {
	wanted, known := required[s.Condition]
	if !known {
		return fmt.Errorf("scenario: %s names the condition %q, which this injector cannot "+
			"dispatch on - the known ones are %s", s.ScenarioID, s.Condition, strings.Join(conditions(), ", "))
	}

	present := s.Parameters.set()
	for _, name := range wanted {
		if !present[name] {
			return fmt.Errorf("scenario: %s is a %s and needs the %s parameter, which is not set",
				s.ScenarioID, s.Condition, name)
		}
	}
	permitted := map[string]bool{}
	for _, name := range wanted {
		permitted[name] = true
	}
	for _, name := range sortedKeys(present) {
		if !permitted[name] {
			return fmt.Errorf("scenario: %s sets %s, which is not a parameter of %s, so the "+
				"injector would ignore it", s.ScenarioID, name, s.Condition)
		}
	}

	if s.HoldsForMinutes <= 0 {
		return fmt.Errorf("scenario: %s is held for %d minutes", s.ScenarioID, s.HoldsForMinutes)
	}
	if int(s.EndsAtMinute()) > bankday.MinutesPerDay {
		return fmt.Errorf("scenario: %s starts at %s and is held for %d minutes, which runs past "+
			"the end of the business day - nothing would revert it",
			s.ScenarioID, s.StartsAtMinute, s.HoldsForMinutes)
	}

	flat := map[string]bool{}
	for _, id := range s.FlatObjectives {
		flat[id] = true
	}
	for _, id := range s.MovesObjectives {
		if flat[id] {
			return fmt.Errorf("scenario: %s expects %s both to move and to stay flat, which no "+
				"observation can contradict", s.ScenarioID, id)
		}
	}

	if len(s.MovesObjectives) == 0 && s.MovesNothingBecause == "" {
		return fmt.Errorf("scenario: %s moves no objective and does not say why - an estate that "+
			"cannot see a condition is a finding, not a blank", s.ScenarioID)
	}
	if len(s.MovesObjectives) > 0 && s.MovesNothingBecause != "" {
		return fmt.Errorf("scenario: %s says it moves nothing while naming %d objective(s) it "+
			"moves", s.ScenarioID, len(s.MovesObjectives))
	}
	if len(s.MovesObjectives) == 0 && len(s.FlatObjectives) == 0 {
		return fmt.Errorf("scenario: %s asserts nothing at all", s.ScenarioID)
	}
	return nil
}

// Find returns the named scenario, or an error naming what the catalogue does hold. A run that
// names one nobody wrote fails at the start of an expensive run, and the answer is one line away.
func (c Catalogue) Find(id string) (Scenario, error) {
	for _, each := range c.Scenarios {
		if each.ScenarioID == id {
			return each, nil
		}
	}
	held := make([]string, 0, len(c.Scenarios))
	for _, each := range c.Scenarios {
		held = append(held, each.ScenarioID)
	}
	return Scenario{}, fmt.Errorf("scenario: no scenario %q in %s; it holds %s",
		id, c.CatalogueID, strings.Join(held, ", "))
}

// Digest is the SHA-256 of the decoded catalogue, hex encoded.
//
// Of the decoded catalogue rather than of the file, so that reindenting the document does not
// invalidate every signature capture that came before it, while any change to what a scenario says
// does. The same reasoning, and the same mechanism, as internal/model.Digest.
func (c Catalogue) Digest() string { return c.digest }

func (c Catalogue) computeDigest() (string, error) {
	c.digest = ""
	canonical, err := json.Marshal(c)
	if err != nil {
		return "", fmt.Errorf("scenario: computing the digest: %w", err)
	}
	sum := sha256.Sum256(canonical)
	return hex.EncodeToString(sum[:]), nil
}

func conditions() []string {
	known := make([]string, 0, len(required))
	for condition := range required {
		known = append(known, string(condition))
	}
	sort.Strings(known)
	return known
}

func sortedKeys(present map[string]bool) []string {
	keys := make([]string, 0, len(present))
	for key := range present {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
