package consumer

import (
	"context"
	"fmt"
	"os/exec"
	"strings"
)

// Container asks the broker through the tools the broker image already ships, inside the container
// the fixture booted. The same shape internal/injector and internal/migration use, and for the same
// reason: the fixture acts on the fixture's own containers, never on the estate's configuration.
type Container struct {
	// Name is the broker container the fixture booted, and nothing else. A driver that could address
	// any container is a driver that could address a real one.
	Name string
	// Bootstrap is the address the tools use from inside the container, which is not the address the
	// host publishes - estate-up.sh advertises PLAINTEXT on the host port and INTERNAL on 9094, and
	// the tools run inside.
	Bootstrap string
}

// Describe runs kafka-consumer-groups for one group.
func (c Container) Describe(ctx context.Context, group string) ([]byte, error) {
	return c.run(ctx, "kafka-consumer-groups",
		"--bootstrap-server", c.bootstrap(),
		"--describe", "--group", group)
}

// EndOffsets runs kafka-get-offsets for one topic. A topic that does not exist answers with an
// error, which Read treats as "nothing was ever produced to it" rather than as a failure - a
// dead-letter topic nobody has written to is the expected state.
func (c Container) EndOffsets(ctx context.Context, topic string) ([]byte, error) {
	return c.run(ctx, "kafka-get-offsets",
		"--bootstrap-server", c.bootstrap(),
		"--topic", topic)
}

func (c Container) bootstrap() string {
	if c.Bootstrap == "" {
		return "localhost:9092"
	}
	return c.Bootstrap
}

func (c Container) run(ctx context.Context, argv ...string) ([]byte, error) {
	if c.Name == "" {
		return nil, fmt.Errorf("consumer: no broker container was named")
	}
	full := append([]string{"exec", c.Name}, argv...)
	out, err := exec.CommandContext(ctx, "docker", full...).CombinedOutput()
	if err != nil {
		// The tool's own message, trimmed, rather than "exit status 1" - a listing that failed
		// because the group has not been created yet reads completely differently from one that
		// failed because the container is gone, and the exit code says neither.
		return out, fmt.Errorf("consumer: docker exec %s %s: %w: %s",
			c.Name, argv[0], err, firstLine(strings.TrimSpace(string(out))))
	}
	return out, nil
}
