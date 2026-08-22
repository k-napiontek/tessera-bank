package hop

import (
	"strings"
	"testing"
	"time"
)

// Real lines, in the format the adapter actually writes. Captured from the jar rather than invented:
// Spring Boot 2.7 has no logback configuration in this module, so its default pattern applies and it
// is `yyyy-MM-dd HH:mm:ss.SSS` followed by two spaces and the level.
const oneTransfer = `
2026-08-22 23:00:59.842  INFO 18328 --- [ntainer#0-0-C-1] bank.tessera.esb.TransferBridge          : transfer TB000000000000000201 carried to the system of record
2026-08-22 23:00:59.912  INFO 18328 --- [ntainer#0-0-C-1] bank.tessera.esb.MovementFileWriter      : transfer TB000000000000000201 written to the movement file as 2 legs at offset 0
2026-08-22 23:00:59.930  INFO 18328 --- [ntainer#0-0-C-1] bank.tessera.esb.TransferBridge          : transfer TB000000000000000201 crossed to stratum 0
`

func TestOneTransferHasBothItsLegs(t *testing.T) {
	crossings, _, err := ParseLog(oneTransfer)
	if err != nil {
		t.Fatalf("the log could not be read: %v", err)
	}
	if len(crossings) != 1 {
		t.Fatalf("crossings = %d, want 1", len(crossings))
	}

	only := crossings[0]
	if only.Ref != "TB000000000000000201" {
		t.Errorf("ref = %q", only.Ref)
	}
	// 23:00:59.842 to 23:00:59.930 is the movement-file append and nothing else.
	if got := only.FileLeg(); got != 88*time.Millisecond {
		t.Errorf("file leg = %v, want 88ms", got)
	}
}

// A carried line with no crossed line after it is a transfer still in flight when the log was read,
// and it is dropped rather than timed against nothing.
func TestATransferStillInFlightIsNotTimed(t *testing.T) {
	crossings, _, err := ParseLog(oneTransfer + `
2026-08-22 23:01:00.100  INFO 18328 --- [ntainer#0-0-C-1] bank.tessera.esb.TransferBridge          : transfer TB000000000000000202 carried to the system of record
`)
	if err != nil {
		t.Fatalf("the log could not be read: %v", err)
	}
	if len(crossings) != 1 {
		t.Fatalf("crossings = %d, want 1 - a transfer with no crossing was timed", len(crossings))
	}
}

func TestTheTwoIdempotentOutcomesAreCounted(t *testing.T) {
	crossings, _, err := ParseLog(`
2026-08-22 23:00:59.842  INFO 1 --- [c] bank.tessera.esb.TransferBridge : transfer TB000000000000000201 carried to the system of record (already applied)
2026-08-22 23:00:59.930  INFO 1 --- [c] bank.tessera.esb.TransferBridge : transfer TB000000000000000201 crossed to stratum 0 (already in the movement file)
`)
	if err != nil {
		t.Fatalf("the log could not be read: %v", err)
	}
	if len(crossings) != 1 {
		t.Fatalf("crossings = %d, want 1", len(crossings))
	}
	if !crossings[0].AlreadyApplied || !crossings[0].AlreadyInFile {
		t.Errorf("a redelivery was not recognised: %+v", crossings[0])
	}
}

func TestTheFailurePathsAreCounted(t *testing.T) {
	_, failures, err := ParseLog(`
2026-08-22 23:00:59.842  WARN 1 --- [c] b.t.esb.TransferPostedListener : transfer TB000000000000000201 could not be carried across at stage SOAP and will be retried: the system of record could not be reached
2026-08-22 23:01:00.842 ERROR 1 --- [c] bank.tessera.esb.DeadLetterRecorder : dead-lettering transfer TB000000000000000202 at stage TRANSFORM: the canonical document is not valid
2026-08-22 23:01:01.842 ERROR 1 --- [c] b.t.esb.TransferPostedListener : transfer TB000000000000000203 failed unexpectedly and will be retried
`)
	if err != nil {
		t.Fatalf("the log could not be read: %v", err)
	}
	if failures.Transient != 1 || failures.DeadLettered != 1 || failures.Unexpected != 1 {
		t.Errorf("failures = %+v, want one of each", failures)
	}
}

// A line the adapter did not write - the broker's, the JVM's, anything - is skipped rather than
// refused. A parser that fell over on Kafka's own INFO lines would never read a real log at all.
func TestUnrelatedLinesAreIgnored(t *testing.T) {
	crossings, _, err := ParseLog(`
2026-08-22 23:00:59.000  INFO 1 --- [main] o.a.k.clients.consumer.ConsumerConfig : ConsumerConfig values:
	allow.auto.create.topics = true
` + oneTransfer)
	if err != nil {
		t.Fatalf("unrelated lines were refused: %v", err)
	}
	if len(crossings) != 1 {
		t.Fatalf("crossings = %d, want 1", len(crossings))
	}
}

// Three transfers, each taking longer to append than the one before - which is the shape a linear
// de-duplication scan over a growing file produces, and the thing this package exists to detect.
func growing() string {
	return `
2026-08-22 23:00:00.000  INFO 1 --- [c] b.t.esb.TransferBridge : transfer TB000000000000000001 carried to the system of record
2026-08-22 23:00:00.010  INFO 1 --- [c] b.t.esb.TransferBridge : transfer TB000000000000000001 crossed to stratum 0
2026-08-22 23:00:00.050  INFO 1 --- [c] b.t.esb.TransferBridge : transfer TB000000000000000002 carried to the system of record
2026-08-22 23:00:00.070  INFO 1 --- [c] b.t.esb.TransferBridge : transfer TB000000000000000002 crossed to stratum 0
2026-08-22 23:00:00.110  INFO 1 --- [c] b.t.esb.TransferBridge : transfer TB000000000000000003 carried to the system of record
2026-08-22 23:00:00.140  INFO 1 --- [c] b.t.esb.TransferBridge : transfer TB000000000000000003 crossed to stratum 0
`
}

func TestTheThreeLegsAreNamedAndOnlyOneIsMeasuredDirectly(t *testing.T) {
	crossings, failures, err := ParseLog(growing())
	if err != nil {
		t.Fatalf("the log could not be read: %v", err)
	}

	report := Summarise(crossings, failures, nil, Conditions{})

	byName := map[string]Leg{}
	for _, leg := range report.Legs {
		byName[leg.Name] = leg
	}
	for _, wanted := range []string{"file", "inbound", "service"} {
		if _, named := byName[wanted]; !named {
			t.Fatalf("no %q leg in %+v", wanted, report.Legs)
		}
	}

	// 10, 20 and 30 ms of appending.
	if got := byName["file"].MeanMillis; got != 20 {
		t.Errorf("file leg mean = %v ms, want 20", got)
	}
	// Two intervals only - the first transfer has nothing before it to be timed from.
	if byName["inbound"].Count != 2 {
		t.Errorf("inbound leg count = %d, want 2", byName["inbound"].Count)
	}
	// The inbound leg is a difference and the report has to say what is inside it, or it will be
	// read as the SOAP call alone.
	if !strings.Contains(byName["inbound"].What, "SOAP") ||
		!strings.Contains(byName["inbound"].What, "XSLT") {
		t.Errorf("the inbound leg does not say what it contains: %q", byName["inbound"].What)
	}
	if !strings.Contains(byName["file"].What, "measured") {
		t.Errorf("the file leg does not say it is measured directly: %q", byName["file"].What)
	}
}

func TestTheCostPerTransferIsReportedAcrossTheDay(t *testing.T) {
	crossings, failures, err := ParseLog(growing())
	if err != nil {
		t.Fatalf("the log could not be read: %v", err)
	}

	report := Summarise(crossings, failures, nil, Conditions{Buckets: 3})
	if len(report.Drift) != 3 {
		t.Fatalf("drift buckets = %d, want 3", len(report.Drift))
	}
	if report.Drift[0].FileMeanMillis != 10 || report.Drift[2].FileMeanMillis != 30 {
		t.Errorf("drift = %v ms then %v ms, want 10 then 30",
			report.Drift[0].FileMeanMillis, report.Drift[2].FileMeanMillis)
	}
	// The claim the write-up will make, computed here rather than eyeballed off a table.
	if report.FileLegGrowth <= 1 {
		t.Errorf("growth = %v, want the last bucket dearer than the first", report.FileLegGrowth)
	}
}

func TestABacklogThatDrainedIsNotABacklogThatDidNot(t *testing.T) {
	drained := Summarise(nil, Failures{}, []Sample{
		{ElapsedSeconds: 0, AdapterAssigned: true, AdapterLag: 0, AdapterLagKnown: true},
		{ElapsedSeconds: 10, AdapterAssigned: true, AdapterLag: 4000, AdapterLagKnown: true},
		{ElapsedSeconds: 60, AdapterAssigned: true, AdapterLag: 0, AdapterLagKnown: true},
	}, Conditions{})
	if !drained.PeakLagSeen || drained.PeakLag != 4000 {
		t.Errorf("peak lag = %d (seen %v), want 4000", drained.PeakLag, drained.PeakLagSeen)
	}
	if !drained.Drained {
		t.Error("a backlog that reached zero was reported as undrained")
	}
	if !strings.Contains(drained.Verdict, "drained") {
		t.Errorf("verdict does not mention draining: %q", drained.Verdict)
	}

	stuck := Summarise(nil, Failures{}, []Sample{
		{ElapsedSeconds: 0, AdapterAssigned: true, AdapterLag: 100, AdapterLagKnown: true},
		{ElapsedSeconds: 60, AdapterAssigned: true, AdapterLag: 9000, AdapterLagKnown: true},
	}, Conditions{})
	if stuck.Drained {
		t.Error("a backlog still standing at the last sample was reported as drained")
	}
	if !strings.Contains(stuck.Verdict, "did not drain") {
		t.Errorf("verdict does not say the backlog stood: %q", stuck.Verdict)
	}
}

// The refusal that makes every other figure worth reading. A group nothing consumes reports a lag
// that looks like any other, and a report that called that "keeping up" would be describing an
// adapter that was never running.
func TestAnUnassignedGroupIsRefusedRatherThanReported(t *testing.T) {
	report := Summarise(nil, Failures{}, []Sample{
		{ElapsedSeconds: 0, AdapterAssigned: false, AdapterLag: 0, AdapterLagKnown: true},
		{ElapsedSeconds: 60, AdapterAssigned: false, AdapterLag: 0, AdapterLagKnown: true},
	}, Conditions{})
	if report.Drained {
		t.Error("a group nothing ever consumed was reported as having drained")
	}
	if !strings.Contains(report.Verdict, "never held") {
		t.Errorf("verdict does not name the unassigned group: %q", report.Verdict)
	}
}

func TestTheRenderedReportCarriesItsConditions(t *testing.T) {
	report := Summarise(nil, Failures{}, nil, Conditions{
		Machine:  "darwin arm64, 10 cores, go1.25.6",
		Endpoint: "http://localhost:18080/customer-master/services/CustomerMasterService",
	})
	rendered := report.Render()
	if !strings.Contains(rendered, "darwin arm64") {
		t.Errorf("the rendered report states no machine:\n%s", rendered)
	}
	if !strings.Contains(rendered, "18080") {
		t.Errorf("the rendered report states no endpoint:\n%s", rendered)
	}
}

// The defect WP-25d's own instrument shipped with, caught by its first real run.
//
// The sampler failed on every call - it asked the broker on the wrong listener - and returned no
// samples at all. The report then printed "peak consumer lag 0" and "closing consumer lag 0",
// because those are the zero values of the fields nothing had filled in. A reader would have taken
// that for a hop that never fell behind, which is precisely the plausible wrong number this
// repository keeps a trap list about: **a lag that was never measured must not be printed as zero.**
func TestAReportWithNoSamplesRefusesToStateALag(t *testing.T) {
	report := Summarise([]Crossing{{Ref: "TB000000000000000201"}}, Failures{}, nil, Conditions{})

	if report.LagMeasured {
		t.Fatal("a report with no samples claims to have measured a lag")
	}
	rendered := report.Render()
	if strings.Contains(rendered, "peak consumer lag       0") {
		t.Errorf("an unmeasured lag is printed as zero:\n%s", rendered)
	}
	if !strings.Contains(rendered, "not measured") {
		t.Errorf("the report does not say the lag was not measured:\n%s", rendered)
	}
	if !strings.Contains(report.Verdict, "no sample") {
		t.Errorf("the verdict does not say the sampler produced nothing: %q", report.Verdict)
	}
}

func TestAReportWithSamplesStatesTheLag(t *testing.T) {
	report := Summarise(nil, Failures{}, []Sample{
		{ElapsedSeconds: 0, AdapterAssigned: true, AdapterLag: 4000, AdapterLagKnown: true},
		{ElapsedSeconds: 60, AdapterAssigned: true, AdapterLag: 0, AdapterLagKnown: true},
	}, Conditions{})

	if !report.LagMeasured {
		t.Fatal("a report with samples does not claim to have measured a lag")
	}
	if !strings.Contains(report.Render(), "peak consumer lag") {
		t.Error("a measured lag is not printed")
	}
}
