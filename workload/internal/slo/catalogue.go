// Package slo reads the committed SLO catalogue and works out what a run did against it.
//
// The catalogue is a contract - contracts/slo/tessera-slo-v1.json - and this decodes it rather than
// restating it. Nothing here knows the name of a metric, a target or a threshold: every one of them
// comes out of the document. A reporting tool that carried its own copy of the objectives would
// agree with itself for exactly as long as nobody edited either, which is the failure F-64 records.
//
// It performs no I/O. The bytes arrive from cmd/workload-report, the same arrangement the model
// itself has, so internal/purity's forbidden list applies here too.
package slo

import (
	"encoding/json"
	"errors"
	"fmt"
)

// FormatID is the catalogue this package understands. Anything else is refused rather than guessed
// at: a consumer that reads a document it does not recognise reports on objectives it has invented.
const FormatID = "TB-SLO-CATALOGUE-V1"

// ErrCatalogue reports a catalogue that cannot be used.
var ErrCatalogue = errors.New("slo: not a catalogue this tool can read")

// Catalogue is the whole document.
type Catalogue struct {
	CatalogueID      string      `json:"catalogueId"`
	CatalogueVersion string      `json:"catalogueVersion"`
	Summary          string      `json:"summary"`
	Components       []Component `json:"components"`
}

// Component is one thing that emits metrics.
type Component struct {
	ComponentID string      `json:"componentId"`
	Path        string      `json:"path"`
	Stratum     string      `json:"stratum"`
	Exposure    string      `json:"exposure"`
	Objectives  []Objective `json:"objectives"`
	Signals     []Signal    `json:"signals"`
}

// Objective is one promise, with the arithmetic that follows from it.
type Objective struct {
	ObjectiveID  string  `json:"objectiveId"`
	Title        string  `json:"title"`
	SLI          SLI     `json:"sli"`
	Target       float64 `json:"target"`
	WindowDays   int     `json:"windowDays"`
	ErrorBudget  Budget  `json:"errorBudget"`
	IntroducedBy string  `json:"introducedBy"`
	Rationale    string  `json:"rationale"`
}

// SLI is what is measured, and how a tool arrives at the figure.
type SLI struct {
	Kind        string   `json:"kind"`
	MeterName   string   `json:"meterName"`
	ExposedName string   `json:"exposedName"`
	Origin      string   `json:"origin"`
	Tags        []string `json:"tags"`

	Threshold  *float64 `json:"threshold"`
	Comparison string   `json:"comparison"`

	ComputedFrom    string   `json:"computedFrom"`
	GoodLabel       string   `json:"goodLabel"`
	GoodValues      []string `json:"goodValues"`
	BadLabelPrefix  string   `json:"badLabelPrefix"`
	ValidAlsoCounts []string `json:"validAlsoCounts"`

	Good  string `json:"good"`
	Valid string `json:"valid"`
}

// Budget is what the objective permits.
type Budget struct {
	Fraction         float64 `json:"fraction"`
	MinutesPerWindow float64 `json:"minutesPerWindow"`
}

// Signal is a metric carrying no objective, and the reason it carries none.
type Signal struct {
	MeterName          string   `json:"meterName"`
	ExposedName        string   `json:"exposedName"`
	Origin             string   `json:"origin"`
	Tags               []string `json:"tags"`
	Meaning            string   `json:"meaning"`
	IntroducedBy       string   `json:"introducedBy"`
	NoObjectiveBecause string   `json:"noObjectiveBecause"`
}

// Decode reads a catalogue, refusing one this tool does not fully understand.
func Decode(document []byte) (Catalogue, error) {
	var catalogue Catalogue
	decoder := json.NewDecoder(newReader(document))
	// Unknown fields are a refusal rather than a shrug: a catalogue that has grown a field this
	// tool ignores is a catalogue this tool is reporting on incompletely, and the report would not
	// say so. The same rule the model's own loader follows.
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&catalogue); err != nil {
		return Catalogue{}, fmt.Errorf("%w: %v", ErrCatalogue, err)
	}
	if catalogue.CatalogueID != FormatID {
		return Catalogue{}, fmt.Errorf(
			"%w: it announces itself as %q and this tool reads %q",
			ErrCatalogue, catalogue.CatalogueID, FormatID)
	}
	if len(catalogue.Components) == 0 {
		return Catalogue{}, fmt.Errorf("%w: it names no components", ErrCatalogue)
	}
	return catalogue, nil
}
