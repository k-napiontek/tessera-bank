package consumer

import (
	"context"
	"errors"
	"strings"
	"testing"
)

// What the broker's own tool prints when the adapter is running and behind. Captured shape rather
// than invented: the columns are the ones kafka-consumer-groups writes, in the order it writes them.
const behind = `
GROUP           TOPIC                              PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                     HOST            CLIENT-ID
esb-adapter     tessera.ledger.transfer-posted.v1  0          412             10877           10465           consumer-esb-adapter-1-9f0c /172.17.0.1     consumer-esb-adapter-1
`

// The same group with nothing consuming it. Kafka prints the offsets it has and a dash in every
// column that describes a member - and the lag is still a number, which is exactly the trap.
const noMembers = `
Consumer group 'esb-adapter' has no active members.

GROUP           TOPIC                              PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
esb-adapter     tessera.ledger.transfer-posted.v1  0          412             10877           10465           -               -               -
`

// A group that has committed nothing at all. CURRENT-OFFSET and LAG are both dashes.
const nothingCommitted = `
GROUP           TOPIC                              PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                 HOST            CLIENT-ID
esb-adapter     tessera.ledger.transfer-posted.v1  0          -               10877           -               consumer-esb-adapter-1-9f0c /172.17.0.1     consumer-esb-adapter-1
`

const missing = "Consumer group 'esb-adapter' does not exist.\n"

const caughtUp = `
GROUP           TOPIC                              PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                 HOST            CLIENT-ID
esb-adapter     tessera.ledger.transfer-posted.v1  0          10877           10877           0               consumer-esb-adapter-1-9f0c /172.17.0.1     consumer-esb-adapter-1
`

func TestTheListingIsReadColumnByName(t *testing.T) {
	group, err := ParseDescribe("esb-adapter", behind)
	if err != nil {
		t.Fatalf("the listing could not be read: %v", err)
	}
	if !group.Exists {
		t.Fatal("a group the broker described was read as absent")
	}
	if len(group.Assignments) != 1 {
		t.Fatalf("assignments = %d, want 1", len(group.Assignments))
	}

	only := group.Assignments[0]
	if only.Topic != "tessera.ledger.transfer-posted.v1" || only.Partition != 0 {
		t.Errorf("topic/partition = %s/%d", only.Topic, only.Partition)
	}
	if only.Current != 412 || only.LogEnd != 10877 || only.Lag != 10465 {
		t.Errorf("offsets = %d/%d lag %d, want 412/10877 lag 10465",
			only.Current, only.LogEnd, only.Lag)
	}
	if only.Member == "" {
		t.Error("a partition with a consumer id was read as unassigned")
	}
}

// The distinction this package exists for. A group with no member and a group that is perfectly
// caught up both produce a small number, and reading either as "keeping up" is the mistake that
// would make every figure in WP-25d worthless.
func TestAGroupWithNoMemberIsNotAGroupThatIsKeepingUp(t *testing.T) {
	idle, err := ParseDescribe("esb-adapter", noMembers)
	if err != nil {
		t.Fatalf("the listing could not be read: %v", err)
	}
	if idle.Assigned() {
		t.Error("a group with no active member was reported as assigned")
	}
	if !idle.Exists {
		t.Error("a group the broker holds offsets for was read as absent")
	}
	// The lag is still real and still worth carrying - what is not true is that anything is working on it.
	if lag, known := idle.TotalLag(); !known || lag != 10465 {
		t.Errorf("total lag = %d (known %v), want 10465", lag, known)
	}

	working, err := ParseDescribe("esb-adapter", caughtUp)
	if err != nil {
		t.Fatalf("the listing could not be read: %v", err)
	}
	if !working.Assigned() {
		t.Error("a group with a consumer id was reported as unassigned")
	}
	if lag, known := working.TotalLag(); !known || lag != 0 {
		t.Errorf("total lag = %d (known %v), want 0", lag, known)
	}
}

// A dash is not a zero. A group that has committed nothing has an unknown lag, and reporting it as
// zero would say the hop had finished work it has not started.
func TestAnUncommittedOffsetIsUnknownRatherThanZero(t *testing.T) {
	group, err := ParseDescribe("esb-adapter", nothingCommitted)
	if err != nil {
		t.Fatalf("the listing could not be read: %v", err)
	}
	only := group.Assignments[0]
	if only.Current != Unknown || only.Lag != Unknown {
		t.Errorf("current/lag = %d/%d, want both %d", only.Current, only.Lag, Unknown)
	}
	if only.LogEnd != 10877 {
		t.Errorf("log end = %d, want 10877", only.LogEnd)
	}
	if _, known := group.TotalLag(); known {
		t.Error("a total lag was reported over a partition whose lag is unknown")
	}
}

func TestAGroupTheBrokerDoesNotHoldIsAbsentRatherThanEmpty(t *testing.T) {
	group, err := ParseDescribe("esb-adapter", missing)
	if err != nil {
		t.Fatalf("a group that does not exist is not an error: %v", err)
	}
	if group.Exists {
		t.Error("a group the broker says does not exist was read as existing")
	}
	if group.Assigned() {
		t.Error("an absent group was reported as assigned")
	}
}

func TestAListingWithNoHeaderIsRefused(t *testing.T) {
	if _, err := ParseDescribe("esb-adapter", "something the tool did not print\n"); err == nil {
		t.Fatal("output with no header row was accepted")
	}
}

// The end offset of the dead-letter topic, which has no consumer group to describe.
func TestTheEndOffsetsAreReadPerPartition(t *testing.T) {
	offsets, err := ParseEndOffsets("tessera.esb.transfer-posted.dlt.v1:0:7\ntessera.esb.transfer-posted.dlt.v1:1:0\n")
	if err != nil {
		t.Fatalf("the offsets could not be read: %v", err)
	}
	if total := offsets.Total(); total != 7 {
		t.Errorf("total = %d, want 7", total)
	}
}

// A topic that exists and holds nothing is zero; the tool prints a dash for a partition it could not
// reach, and that is not zero either.
func TestAnUnreachablePartitionIsNotAnEmptyOne(t *testing.T) {
	if _, err := ParseEndOffsets("tessera.esb.transfer-posted.dlt.v1:0:-\n"); err == nil {
		t.Fatal("a partition with no offset was read as empty")
	}
}

type recorder struct {
	describe map[string]string
	offsets  string
	calls    []string
	fail     error
}

func (r *recorder) Describe(_ context.Context, group string) ([]byte, error) {
	r.calls = append(r.calls, "describe "+group)
	if r.fail != nil {
		return nil, r.fail
	}
	return []byte(r.describe[group]), nil
}

func (r *recorder) EndOffsets(_ context.Context, topic string) ([]byte, error) {
	r.calls = append(r.calls, "offsets "+topic)
	if r.fail != nil {
		return nil, r.fail
	}
	return []byte(r.offsets), nil
}

// The whole package is exercised against a recorder, so make test-workload needs no broker - the
// property the Makefile's own comment makes a design decision rather than a convenience.
func TestTheBrokerIsAskedThroughAnInterface(t *testing.T) {
	broker := &recorder{
		describe: map[string]string{"esb-adapter": behind, "fraud-scoring": caughtUp},
		offsets:  "tessera.esb.transfer-posted.dlt.v1:0:3\n",
	}

	reading, err := Read(context.Background(), broker,
		[]string{"esb-adapter", "fraud-scoring"}, "tessera.esb.transfer-posted.dlt.v1")
	if err != nil {
		t.Fatalf("the reading failed: %v", err)
	}
	if len(reading.Groups) != 2 {
		t.Fatalf("groups = %d, want 2", len(reading.Groups))
	}
	if reading.DeadLetters != 3 {
		t.Errorf("dead letters = %d, want 3", reading.DeadLetters)
	}
	if got := strings.Join(broker.calls, ", "); !strings.Contains(got, "describe esb-adapter") {
		t.Errorf("the broker was not asked about the adapter: %s", got)
	}
}

func TestABrokerThatCannotBeReachedIsAnError(t *testing.T) {
	broker := &recorder{fail: errors.New("no such container")}
	if _, err := Read(context.Background(), broker, []string{"esb-adapter"}, "dlt"); err == nil {
		t.Fatal("a broker that could not be reached was reported as a reading")
	}
}
