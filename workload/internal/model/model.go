// Package model decodes a workload model and turns it into the engine's types.
//
// It performs no file I/O. Decode takes bytes, because the two things that load a model load it
// from different places - the planning tool from a path, and a driver from wherever it is
// configured - and because a package that opens files is a package the purity test has to argue
// with. The engine holds no path, no handle and no connection, which is the same shape batch/recon's
// compare module was given so that REQ-REC-003 was enforced by the code rather than promised by it.
//
// It also repeats two of the contract checker's cross-checks: that the population generates the
// volume the model declares, and that the curve has the peak-to-trough ratio declared beside it.
// That is deliberate duplication. contracts/check-workload-model.py proves the document is coherent
// before it is committed; this proves the loader read it the same way, and it runs against whatever
// model WP-21 is pointed at rather than only against the one that went through validate.sh.
package model

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
)

// This file names float64, and internal/money/source_test.go requires a recorded reason. The reason
// is that weights, multipliers and the diurnal curve arrive from the model as JSON numbers. The one
// money field in the document - an amount in minor units - is decoded as int64 and stays one.

// FormatID is the only model format this engine understands.
const FormatID = "TB-WORKLOAD-DAY-V1"

// The tolerances the loader holds the document to, matching contracts/check-workload-model.py.
const (
	weightTolerance = 1e-9
	ratioTolerance  = 1e-9
)

// Model is a decoded workload model.
type Model struct {
	ModelID      string   `json:"modelId"`
	ModelVersion string   `json:"modelVersion"`
	Summary      string   `json:"summary"`
	RealTime     RealTime `json:"realTime"`
	Calendar     Calendar `json:"calendar"`
	Population   PopSpec  `json:"population"`
	digest       string   `json:"-"`
}

// RealTime is what the model asks for at scale 1.0 and compression 1.
type RealTime struct {
	DailyEventCount   int64   `json:"dailyEventCount"`
	PeakToTroughRatio float64 `json:"peakToTroughRatio"`
}

// Calendar is when demand happens.
type Calendar struct {
	Diurnal  []float64          `json:"diurnal"`
	Weekday  map[string]float64 `json:"weekday"`
	Payday   Payday             `json:"payday"`
	MonthEnd MonthEnd           `json:"monthEnd"`
	Windows  []WindowSpec       `json:"windows"`
	Instants []InstantSpec      `json:"instants"`
}

// Payday names the salary dates and what they do to a day.
type Payday struct {
	DaysOfMonth []int   `json:"daysOfMonth"`
	Multiplier  float64 `json:"multiplier"`
}

// MonthEnd names the closing band of the month.
type MonthEnd struct {
	LastDays   int     `json:"lastDays"`
	Multiplier float64 `json:"multiplier"`
}

// WindowSpec is a named span of the business day.
type WindowSpec struct {
	ID          string `json:"id"`
	StartMinute int    `json:"startMinute"`
	EndMinute   int    `json:"endMinute"`
	Purpose     string `json:"purpose"`
}

// InstantSpec is a named moment of the business day.
type InstantSpec struct {
	ID       string `json:"id"`
	AtMinute int    `json:"atMinute"`
	Purpose  string `json:"purpose"`
}

// PopSpec is who generates the demand.
type PopSpec struct {
	Size                int          `json:"size"`
	AccountsPerCustomer int          `json:"accountsPerCustomer"`
	Cohorts             []CohortSpec `json:"cohorts"`
}

// CohortSpec is a class of customer and how it behaves.
type CohortSpec struct {
	ID                      string             `json:"id"`
	Share                   float64            `json:"share"`
	EventsPerCustomerPerDay int                `json:"eventsPerCustomerPerDay"`
	Amount                  AmountSpec         `json:"amount"`
	CurrencyMix             map[string]float64 `json:"currencyMix"`
	OperationMix            map[string]float64 `json:"operationMix"`
}

// AmountSpec is a cohort's transfer size, in minor units throughout.
type AmountSpec struct {
	MedianMinor int64   `json:"medianMinor"`
	Sigma       float64 `json:"sigma"`
	MinMinor    int64   `json:"minMinor"`
	MaxMinor    int64   `json:"maxMinor"`
}

var weekdays = map[string]time.Weekday{
	"sunday": time.Sunday, "monday": time.Monday, "tuesday": time.Tuesday,
	"wednesday": time.Wednesday, "thursday": time.Thursday, "friday": time.Friday,
	"saturday": time.Saturday,
}

// Decode reads a workload model and refuses one it does not fully understand.
//
// Unknown fields are an error rather than something to ignore. A model with a mistyped field would
// otherwise load with that setting silently at its zero value, and the run report would describe a
// day nobody asked for - the failure mode this repository's trap list is entirely made of.
func Decode(document []byte) (Model, error) {
	decoder := json.NewDecoder(bytes.NewReader(document))
	decoder.DisallowUnknownFields()

	var loaded Model
	if err := decoder.Decode(&loaded); err != nil {
		return Model{}, fmt.Errorf("model: %w", err)
	}
	if loaded.ModelID != FormatID {
		return Model{}, fmt.Errorf("model: modelId is %q, and this engine reads %s only",
			loaded.ModelID, FormatID)
	}
	if err := loaded.validate(); err != nil {
		return Model{}, err
	}

	digest, err := loaded.computeDigest()
	if err != nil {
		return Model{}, err
	}
	loaded.digest = digest
	return loaded, nil
}

func (m Model) validate() error {
	if len(m.Calendar.Diurnal) != 24 {
		return fmt.Errorf("model: the diurnal curve has %d hours, and a day has 24",
			len(m.Calendar.Diurnal))
	}
	for name := range m.Calendar.Weekday {
		if _, known := weekdays[name]; !known {
			return fmt.Errorf("model: %q is not a day of the week", name)
		}
	}
	if len(m.Calendar.Weekday) != len(weekdays) {
		return fmt.Errorf("model: %d weekday multipliers, and a week has %d",
			len(m.Calendar.Weekday), len(weekdays))
	}

	curve, err := m.Curve()
	if err != nil {
		return err
	}
	if ratio := curve.PeakToTrough(); math.Abs(ratio-m.RealTime.PeakToTroughRatio) > ratioTolerance {
		return fmt.Errorf(
			"model: the curve's peak-to-trough is %v and realTime.peakToTroughRatio declares %v",
			ratio, m.RealTime.PeakToTroughRatio)
	}

	people, err := m.People()
	if err != nil {
		return err
	}
	generated := int64(0)
	for _, cohort := range people.Cohorts() {
		members := int64(math.Round(cohort.Share * float64(m.Population.Size)))
		generated += members * int64(cohort.EventsPerCustomerPerDay)
	}
	if generated != m.RealTime.DailyEventCount {
		return fmt.Errorf(
			"model: the population generates %d events a day and realTime.dailyEventCount declares %d",
			generated, m.RealTime.DailyEventCount)
	}

	for _, cohort := range m.Population.Cohorts {
		if err := sumsToOne(cohort.ID, "currencyMix", cohort.CurrencyMix); err != nil {
			return err
		}
		if err := sumsToOne(cohort.ID, "operationMix", cohort.OperationMix); err != nil {
			return err
		}
	}
	return nil
}

func sumsToOne(cohort, what string, weights map[string]float64) error {
	total := 0.0
	for _, weight := range weights {
		total += weight
	}
	if math.Abs(total-1) > weightTolerance {
		return fmt.Errorf("model: cohort %q has a %s adding to %v rather than 1", cohort, what, total)
	}
	return nil
}

// Curve composes the calendar into an intensity function.
func (m Model) Curve() (bankday.Curve, error) {
	var diurnal [24]float64
	if len(m.Calendar.Diurnal) != 24 {
		return bankday.Curve{}, fmt.Errorf("model: the diurnal curve has %d hours",
			len(m.Calendar.Diurnal))
	}
	copy(diurnal[:], m.Calendar.Diurnal)

	weekday := make(map[time.Weekday]float64, len(m.Calendar.Weekday))
	for name, multiplier := range m.Calendar.Weekday {
		day, known := weekdays[name]
		if !known {
			return bankday.Curve{}, fmt.Errorf("model: %q is not a day of the week", name)
		}
		weekday[day] = multiplier
	}

	return bankday.NewCurve(bankday.CurveSpec{
		Diurnal:         diurnal,
		Weekday:         weekday,
		PaydayDays:      m.Calendar.Payday.DaysOfMonth,
		PaydayFactor:    m.Calendar.Payday.Multiplier,
		MonthEndDays:    m.Calendar.MonthEnd.LastDays,
		MonthEndFactor:  m.Calendar.MonthEnd.Multiplier,
		DailyEventCount: m.RealTime.DailyEventCount,
	})
}

// People builds the population this model describes.
//
// The mixes arrive as maps and leave as slices, sorted by key. A Go map iterates in a deliberately
// random order, so a weighted pick over one would give a different answer per process - and
// "reproducible from the manifest" would be false in a way no single test run could show.
func (m Model) People() (population.Population, error) {
	cohorts := make([]population.Cohort, 0, len(m.Population.Cohorts))
	for _, spec := range m.Population.Cohorts {
		currencies := make([]population.Weighted[money.Currency], 0, len(spec.CurrencyMix))
		for _, code := range sortedKeys(spec.CurrencyMix) {
			currencies = append(currencies, population.Weighted[money.Currency]{
				Value: money.Currency(code), Weight: spec.CurrencyMix[code],
			})
		}
		operations := make([]population.Weighted[string], 0, len(spec.OperationMix))
		for _, name := range sortedKeys(spec.OperationMix) {
			operations = append(operations, population.Weighted[string]{
				Value: name, Weight: spec.OperationMix[name],
			})
		}
		cohorts = append(cohorts, population.Cohort{
			ID:                      spec.ID,
			Share:                   spec.Share,
			EventsPerCustomerPerDay: spec.EventsPerCustomerPerDay,
			Amount: population.AmountSpec{
				MedianMinor: spec.Amount.MedianMinor,
				Sigma:       spec.Amount.Sigma,
				MinMinor:    spec.Amount.MinMinor,
				MaxMinor:    spec.Amount.MaxMinor,
			},
			Currencies: currencies,
			Operations: operations,
		})
	}
	return population.New(population.Spec{
		Size:                m.Population.Size,
		AccountsPerCustomer: m.Population.AccountsPerCustomer,
		Cohorts:             cohorts,
	})
}

// Windows returns the named spans of the business day.
func (m Model) Windows() []bankday.Window {
	windows := make([]bankday.Window, 0, len(m.Calendar.Windows))
	for _, spec := range m.Calendar.Windows {
		windows = append(windows, bankday.Window{
			ID:      spec.ID,
			Start:   bankday.Minute(spec.StartMinute),
			End:     bankday.Minute(spec.EndMinute),
			Purpose: spec.Purpose,
		})
	}
	return windows
}

// Window finds one named span.
func (m Model) Window(id string) (bankday.Window, bool) {
	for _, window := range m.Windows() {
		if window.ID == id {
			return window, true
		}
	}
	return bankday.Window{}, false
}

// Instants returns the named moments of the business day.
func (m Model) Instants() []bankday.Instant {
	instants := make([]bankday.Instant, 0, len(m.Calendar.Instants))
	for _, spec := range m.Calendar.Instants {
		instants = append(instants, bankday.Instant{
			ID:      spec.ID,
			At:      bankday.Minute(spec.AtMinute),
			Purpose: spec.Purpose,
		})
	}
	return instants
}

// Instant finds one named moment.
func (m Model) Instant(id string) (bankday.Instant, bool) {
	for _, instant := range m.Instants() {
		if instant.ID == id {
			return instant, true
		}
	}
	return bankday.Instant{}, false
}

// Digest is the SHA-256 of the decoded model, hex encoded.
//
// Of the decoded model rather than of the file, so that reindenting the document does not
// invalidate every run report that came before it, while any change to what the model says does.
// encoding/json writes struct fields in declaration order and map keys in sorted order, which is
// what makes the re-encoding canonical.
func (m Model) Digest() string { return m.digest }

func (m Model) computeDigest() (string, error) {
	m.digest = ""
	canonical, err := json.Marshal(m)
	if err != nil {
		return "", fmt.Errorf("model: computing the digest: %w", err)
	}
	sum := sha256.Sum256(canonical)
	return hex.EncodeToString(sum[:]), nil
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	for i := 1; i < len(keys); i++ {
		for j := i; j > 0 && keys[j] < keys[j-1]; j-- {
			keys[j], keys[j-1] = keys[j-1], keys[j]
		}
	}
	return keys
}
