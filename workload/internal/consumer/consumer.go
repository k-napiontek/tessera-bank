// Package consumer reads how far behind a Kafka consumer group is, by asking the broker.
//
// # Why this shells out rather than speaking the protocol
//
// This module carries no dependencies at all - internal/metrics writes Prometheus exposition by
// hand for the same reason - and a Kafka client would be the first. The broker ships the tool that
// answers this question, the fixture already runs `kafka-topics` and `kafka-broker-api-versions`
// inside the same container, and `internal/migration` already reads `pg_locks` by parsing what psql
// printed. So this parses `kafka-consumer-groups --describe`, which is one more of the same, rather
// than adding a client library to a module whose zero-dependency property is load-bearing.
//
// # The distinction the whole package exists for
//
// **A group with no active member and a group that is perfectly caught up both report a small
// number.** An adapter that never subscribed, one that died halfway through the day and one that is
// keeping up are three completely different findings, and the lag column alone tells them apart in
// none of them. So an assignment carries who holds it, Assigned reports whether anything at all is
// working the group, and a dash is read as Unknown rather than as zero. WP-24a's SCN-CONSUMER-LAG
// capture records the same shape from the other end: nothing in the estate is stated over the gap
// between what the ledger published and what a consumer read, so this gap has never been measured.
//
// # What it does not do
//
// It reads. It never resets an offset, never deletes a group and never produces to a topic - a
// fixture that could move a consumer's position could quietly repair the thing it is measuring.
package consumer

import (
	"context"
	"fmt"
	"strconv"
	"strings"
)

// Unknown is what a dash in the broker's own output means: the offset or the lag is not known, which
// is not the same as being zero. Reporting an unknown lag as zero would say the hop had finished
// work it has not started.
const Unknown int64 = -1

// Broker is what this package may ask of the running broker. An interface rather than a docker call,
// so the parsing is tested against captured output and `make test-workload` needs no Kafka.
type Broker interface {
	Describe(ctx context.Context, group string) ([]byte, error)
	EndOffsets(ctx context.Context, topic string) ([]byte, error)
}

// Assignment is one partition of one topic, as one consumer group sees it.
type Assignment struct {
	Topic     string `json:"topic"`
	Partition int    `json:"partition"`
	Current   int64  `json:"currentOffset"`
	LogEnd    int64  `json:"logEndOffset"`
	Lag       int64  `json:"lag"`
	// Member is the consumer id holding the partition, empty when nothing holds it. It is the field
	// that separates "caught up" from "not running", and both of those look like a small lag.
	Member string `json:"member,omitempty"`
}

// Group is one consumer group at one instant.
type Group struct {
	Name        string       `json:"group"`
	Exists      bool         `json:"exists"`
	Assignments []Assignment `json:"assignments"`
}

// Assigned reports whether anything at all is working this group. False for a group the broker holds
// offsets for but nobody consumes - which is a stalled hop, not a fast one.
func (g Group) Assigned() bool {
	for _, assignment := range g.Assignments {
		if assignment.Member != "" {
			return true
		}
	}
	return false
}

// TotalLag sums the lag across partitions, and says so when it cannot. One partition whose lag is
// unknown makes the total unknown: a sum that silently skips a partition is a smaller number that
// looks like the same measurement.
func (g Group) TotalLag() (int64, bool) {
	if !g.Exists || len(g.Assignments) == 0 {
		return 0, false
	}
	var total int64
	for _, assignment := range g.Assignments {
		if assignment.Lag == Unknown {
			return 0, false
		}
		total += assignment.Lag
	}
	return total, true
}

// Offsets is a topic's end offset per partition.
type Offsets map[int]int64

// Total is how many records the topic holds across every partition.
func (o Offsets) Total() int64 {
	var total int64
	for _, offset := range o {
		total += offset
	}
	return total
}

// Reading is one sample of every group and the dead-letter topic together, so a sample is one
// instant rather than several taken as the run moved underneath it.
type Reading struct {
	Groups      []Group `json:"groups"`
	DeadLetters int64   `json:"deadLetters"`
}

// Group returns the named group's reading.
func (r Reading) Group(name string) (Group, bool) {
	for _, group := range r.Groups {
		if group.Name == name {
			return group, true
		}
	}
	return Group{}, false
}

// Read asks the broker about every group and the dead-letter topic.
func Read(ctx context.Context, broker Broker, groups []string, deadLetterTopic string) (Reading, error) {
	reading := Reading{Groups: make([]Group, 0, len(groups))}

	for _, name := range groups {
		output, err := broker.Describe(ctx, name)
		if err != nil {
			return Reading{}, fmt.Errorf("consumer: the broker could not describe %s: %w", name, err)
		}
		group, err := ParseDescribe(name, string(output))
		if err != nil {
			return Reading{}, err
		}
		reading.Groups = append(reading.Groups, group)
	}

	if deadLetterTopic != "" {
		output, err := broker.EndOffsets(ctx, deadLetterTopic)
		if err != nil {
			return Reading{}, fmt.Errorf("consumer: the broker could not report offsets for %s: %w",
				deadLetterTopic, err)
		}
		// A dead-letter topic nothing has ever produced to may not exist, and that is the expected
		// state rather than a failure - it means no transfer was ever refused permanently.
		offsets, err := ParseEndOffsets(string(output))
		if err == nil {
			reading.DeadLetters = offsets.Total()
		}
	}

	return reading, nil
}

// ParseDescribe reads what `kafka-consumer-groups --describe --group <name>` printed.
//
// The columns are found by name rather than by position. Every Kafka version so far prints them in
// the same order, and a parser that assumes so is one release away from reading the log-end offset
// as the lag - silently, and with a plausible number.
func ParseDescribe(name, output string) (Group, error) {
	group := Group{Name: name}

	if strings.Contains(output, "does not exist") {
		return group, nil
	}

	var columns map[string]int
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}

		if columns == nil {
			if fields[0] == "GROUP" {
				columns = map[string]int{}
				for at, header := range fields {
					columns[header] = at
				}
			}
			continue
		}

		// Only the rows for the group asked about. The tool prints one group here, but a listing
		// concatenated from several would otherwise be summed into one figure.
		if fields[0] != name {
			continue
		}
		assignment, err := assignmentOf(fields, columns)
		if err != nil {
			return Group{}, err
		}
		group.Assignments = append(group.Assignments, assignment)
	}

	if columns == nil {
		return Group{}, fmt.Errorf(
			"consumer: %s has no GROUP header row, so the columns cannot be located: %q",
			name, firstLine(output))
	}
	group.Exists = true
	return group, nil
}

func assignmentOf(fields []string, columns map[string]int) (Assignment, error) {
	partition, err := number(fields, columns, "PARTITION")
	if err != nil {
		return Assignment{}, err
	}
	current, err := number(fields, columns, "CURRENT-OFFSET")
	if err != nil {
		return Assignment{}, err
	}
	logEnd, err := number(fields, columns, "LOG-END-OFFSET")
	if err != nil {
		return Assignment{}, err
	}
	lag, err := number(fields, columns, "LAG")
	if err != nil {
		return Assignment{}, err
	}

	assignment := Assignment{
		Topic:     text(fields, columns, "TOPIC"),
		Partition: int(partition),
		Current:   current,
		LogEnd:    logEnd,
		Lag:       lag,
	}
	// A dash in CONSUMER-ID is the broker saying nothing holds this partition, which is the whole
	// point of carrying the field.
	if member := text(fields, columns, "CONSUMER-ID"); member != "-" {
		assignment.Member = member
	}
	return assignment, nil
}

func text(fields []string, columns map[string]int, header string) string {
	at, named := columns[header]
	if !named || at >= len(fields) {
		return ""
	}
	return fields[at]
}

// number reads one column, treating the tool's dash as Unknown rather than as zero.
func number(fields []string, columns map[string]int, header string) (int64, error) {
	raw := text(fields, columns, header)
	if raw == "" {
		return Unknown, fmt.Errorf("consumer: the listing has no %s column", header)
	}
	if raw == "-" {
		return Unknown, nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return Unknown, fmt.Errorf("consumer: %s is %q, which is not a number", header, raw)
	}
	return value, nil
}

// ParseEndOffsets reads what `kafka-get-offsets --topic <t>` printed: topic:partition:offset, one
// per line. A partition the tool could not answer for prints a dash, and that is refused rather than
// counted as an empty partition - a dead-letter topic read as empty when it could not be read at all
// would report that nothing was refused.
func ParseEndOffsets(output string) (Offsets, error) {
	offsets := Offsets{}

	for _, line := range strings.Split(output, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.Split(line, ":")
		if len(parts) != 3 {
			return nil, fmt.Errorf("consumer: %q is not topic:partition:offset", line)
		}
		partition, err := strconv.Atoi(parts[1])
		if err != nil {
			return nil, fmt.Errorf("consumer: %q has no partition number", line)
		}
		offset, err := strconv.ParseInt(parts[2], 10, 64)
		if err != nil {
			return nil, fmt.Errorf(
				"consumer: partition %d of %s reports %q rather than an offset", partition, parts[0], parts[2])
		}
		offsets[partition] = offset
	}

	if len(offsets) == 0 {
		return nil, fmt.Errorf("consumer: no offsets were reported")
	}
	return offsets, nil
}

func firstLine(output string) string {
	for _, line := range strings.Split(output, "\n") {
		if strings.TrimSpace(line) != "" {
			return strings.TrimSpace(line)
		}
	}
	return ""
}
