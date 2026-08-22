package migration

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"github.com/k-napiontek/tessera-bank/workload/internal/injector"
)

// Local is the real fixture: Docker on this machine.
//
// It embeds *injector.Local rather than reimplementing docker exec, so there is exactly one place
// in this module that knows how to reach into the database container. F-61, F-64 and F-66 each
// record a second copy of something rotting, and a second copy of this one would drift on the
// PGPASSWORD alone.
type Local struct {
	*injector.Local
}

// RunImage runs a one-shot container joined to another container's network.
//
// Joined rather than pointed at a published port, because a published port reaches the database
// differently on macOS and on Linux, and an exercise that only works on the machine it was written
// on is not an exercise anybody else can repeat. Inside the shared namespace the database is always
// localhost:5432, whatever TB_DB_PORT the fixture published it on outside.
//
// The credentials on the command line are the fixture's synthetic ones - the same tessera/tessera
// estate-up.sh passes to the container in the clear. There is nothing here that is not already in
// the boot script, and no personal data anywhere near it.
func (l Local) RunImage(ctx context.Context, image, joinNetworkOf, mountHostDir string, argv ...string) ([]byte, error) {
	absolute, err := absolutePath(mountHostDir)
	if err != nil {
		return nil, err
	}
	full := append([]string{
		"run", "--rm",
		"--network", "container:" + joinNetworkOf,
		"-v", absolute + ":/flyway/sql:ro",
		image,
	}, argv...)

	command := exec.CommandContext(ctx, "docker", full...)
	out, err := command.CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("migration: docker run %s: %w", image, err)
	}
	return out, nil
}

// absolutePath, because docker -v refuses a relative one with a message about named volumes that
// says nothing about the actual mistake.
func absolutePath(dir string) (string, error) {
	info, err := os.Stat(dir)
	if err != nil {
		return "", fmt.Errorf("migration: the migrations directory %s: %w", dir, err)
	}
	if !info.IsDir() {
		return "", fmt.Errorf("migration: %s is not a directory", dir)
	}
	resolved, err := filepath.Abs(dir)
	if err != nil {
		return "", fmt.Errorf("migration: resolving %s: %w", dir, err)
	}
	return resolved, nil
}

func readFile(path string) (string, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return string(content), nil
}
