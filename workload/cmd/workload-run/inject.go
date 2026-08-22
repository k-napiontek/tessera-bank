package main

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/k-napiontek/tessera-bank/workload/internal/bankday"
	"github.com/k-napiontek/tessera-bank/workload/internal/client"
	"github.com/k-napiontek/tessera-bank/workload/internal/injector"
	"github.com/k-napiontek/tessera-bank/workload/internal/money"
	"github.com/k-napiontek/tessera-bank/workload/internal/population"
	"github.com/k-napiontek/tessera-bank/workload/internal/proxy"
	"github.com/k-napiontek/tessera-bank/workload/internal/scenario"
)

// loadScenario reads the catalogue and picks the condition this run was asked for. Both flags or
// neither: a catalogue with no identifier is a file nobody chose from, and an identifier with no
// catalogue is a name nothing can resolve.
func loadScenario(opts options) (scenario.Scenario, string, bool, error) {
	if opts.scenarioPath == "" && opts.scenarioID == "" {
		return scenario.Scenario{}, "", false, nil
	}
	if opts.scenarioPath == "" || opts.scenarioID == "" {
		return scenario.Scenario{}, "", false,
			fmt.Errorf("--scenario and --scenario-id go together, and only one is set")
	}
	document, err := os.ReadFile(opts.scenarioPath)
	if err != nil {
		return scenario.Scenario{}, "", false, err
	}
	catalogue, err := scenario.Decode(document)
	if err != nil {
		return scenario.Scenario{}, "", false, err
	}
	chosen, err := catalogue.Find(opts.scenarioID)
	if err != nil {
		return scenario.Scenario{}, "", false, err
	}
	// The catalogue's digest rather than the scenario's own: what has to be reproducible is the
	// document the condition was read out of, exactly as the model digest is the document the day
	// was read out of.
	return chosen, catalogue.Digest(), true, nil
}

// newInjector wires the injector to what this process holds: the hop it hosts, the storm it can
// offer, and the containers and process groups estate-up.sh started.
func newInjector(opts options, hop *proxy.Proxy, weather injector.Storm) (*injector.Injector, error) {
	settings := injector.Settings{
		Fixture:           injector.Local{RunDir: opts.runDir},
		Storm:             weather,
		BrokerContainer:   opts.brokerContainer,
		DatabaseContainer: opts.dbContainer,
		Compression:       opts.compress,
		Log:               func(format string, args ...any) { fmt.Printf(format, args...) },
	}
	// A typed nil would satisfy the interface and answer every call, which is the one way this
	// wiring can be wrong without saying so.
	if hop != nil {
		settings.Proxy = hop
	}
	return injector.New(settings)
}

// storm offers extra requests at the gateway beside the day the run is executing.
//
// It lives here rather than in internal/injector because everything it needs - the sender, the
// wallet behind it, and the accounts this run actually seeded - is held by the driver, and a second
// copy of any of them in the injector is the duplication F-61, F-64 and F-66 each record rotting.
//
// It sends balance enquiries. A storm of reads exercises the limiter, which is keyed by subject and
// route class per ADR 0006, without moving money the reconciliation would then have to explain -
// the condition under test is refusal, not throughput.
type storm struct {
	sender   *client.Sender
	date     bankday.Date
	held     money.Currency
	holdings []population.Holding
}

// newStorm takes the customer accounts this run has, treasury excluded: the treasury is the bank's
// and a storm against it would be the bank rate-limiting itself.
func newStorm(sender *client.Sender, people population.Population, date bankday.Date, held money.Currency) storm {
	var holdings []population.Holding
	for holding := range people.Accounts() {
		if holding.Treasury {
			continue
		}
		holdings = append(holdings, holding)
		if len(holdings) >= stormSubjectCeiling {
			break
		}
	}
	return storm{sender: sender, date: date, held: held, holdings: holdings}
}

// stormSubjectCeiling bounds how many accounts are held for a storm. The schema caps subjects at
// 1000 and a run's population is far larger, so taking the whole of it would be a slice nobody uses.
const stormSubjectCeiling = 1000

// Start offers perSecond requests a second, spread over the first subjects accounts, until stop is
// called. Requests are fired and not awaited: the model is open, and a storm that waited for its
// own replies would slow down exactly when the limiter started refusing - which is the coordinated
// omission ADR 0016 exists to keep out of this module.
func (s storm) Start(ctx context.Context, perSecond, subjects int) func() {
	if perSecond <= 0 || len(s.holdings) == 0 {
		return func() {}
	}
	subjects = min(max(subjects, 1), len(s.holdings))

	ctx, cancel := context.WithCancel(ctx)
	var running sync.WaitGroup
	running.Add(1)

	go func() {
		defer running.Done()
		ticker := time.NewTicker(time.Second / time.Duration(perSecond))
		defer ticker.Stop()
		var inFlight sync.WaitGroup
		for seq := 0; ; seq++ {
			select {
			case <-ctx.Done():
				inFlight.Wait()
				return
			case at := <-ticker.C:
				holding := s.holdings[seq%subjects]
				request, err := client.Build(population.Action{
					CustomerRef: holding.CustomerRef,
					AccountRef:  holding.AccountRef,
					Cohort:      holding.Cohort,
					Operation:   "getBalance",
				}, s.date, int64(seq), s.held, nothingYet{})
				if err != nil {
					continue
				}
				inFlight.Add(1)
				go func() {
					defer inFlight.Done()
					s.sender.Send(ctx, request, at)
				}()
			}
		}
	}()

	return func() {
		cancel()
		running.Wait()
	}
}

// nothingYet answers every reference question with "not yet". A balance enquiry names an account the
// population already knows, so the storm never has to be told what the run created.
type nothingYet struct{}

func (nothingYet) Transfer() (client.Transfer, bool)     { return client.Transfer{}, false }
func (nothingYet) TakeTransfer() (client.Transfer, bool) { return client.Transfer{}, false }
func (nothingYet) Hold() (client.Hold, bool)             { return client.Hold{}, false }

// printInjection says what the condition did, and says the honest thing when it could not be
// produced. A run that quietly skipped a condition would report a signature of a healthy estate
// under the name of a degraded one.
func printInjection(record injector.Record, each scenario.Scenario) {
	fmt.Printf("\n== Condition ==\n  %s - %s\n", record.ScenarioID, each.Title)
	if !record.Injected {
		fmt.Printf("  NOT INJECTED. %s\n", record.NotInjectableBecause)
		fmt.Printf("  Recorded as a finding about testability rather than treated as a failure,\n")
		fmt.Printf("  per WP-24's Constraint. Everything else in this run is a normal run.\n")
		return
	}
	fmt.Printf("  applied %s into the window and held for %s\n",
		record.AppliedAfter.Round(time.Millisecond), record.HeldFor.Round(time.Millisecond))
	fmt.Printf("  expected to move: %v\n", each.MovesObjectives)
	fmt.Printf("  expected to stay flat: %v\n", each.FlatObjectives)
}
