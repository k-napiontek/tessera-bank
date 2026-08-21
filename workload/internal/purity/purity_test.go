// Package purity holds the architectural controls that belong to the module rather than to any one
// package in it.
//
// The precedent is services/ledger-core's DomainPurityTest, which enforces the zero-framework rule
// against the source rather than asserting it in a comment. The reasoning transfers exactly: a
// constraint checked crudely holds, and a constraint written in a document does not.
package purity

import (
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
)

const (
	moduleRoot = "../.."
	modulePath = "github.com/k-napiontek/tessera-bank/workload"
)

// What the engine may not reach, and why each one matters here.
//
// WP-20 is explicit that this package "performs no I/O of any kind" and that it "sends nothing and
// drives nothing" - it emits a schedule and WP-21 executes it. That boundary is the whole reason
// two drivers can share one model: a model that could open a socket would eventually contain half a
// driver, and the second driver would then be written against the other half.
var forbidden = map[string]string{
	"net":          "the engine sends nothing - WP-21 and WP-25 do",
	"net/http":     "the engine sends nothing - WP-21 and WP-25 do",
	"net/url":      "the engine names no endpoint, so it has no URL to build",
	"os":           "the engine reads no file; Decode takes bytes and cmd supplies them",
	"os/exec":      "nothing here shells out",
	"os/signal":    "a schedule has no lifecycle to interrupt",
	"database/sql": "the engine holds no connection, the same shape batch/recon's compare was given",
	"syscall":      "there is no system call a pure arithmetic engine needs",
	"io/ioutil":    "deprecated, and it exists only to read and write files",
}

// engine is the model: the packages WP-20 built, which perform no I/O of any kind. The list is
// named rather than discovered, because "every package that is not under cmd/" stopped being the
// right rule the moment WP-21 added a driver to the same module. A new package is in neither list
// until somebody decides which side of the line it belongs on, and TestEveryPackageIsClassified
// fails until they do.
var engine = []string{
	"internal/arrivals",
	"internal/bankday",
	"internal/manifest",
	"internal/model",
	"internal/money",
	"internal/population",
}

// driver is WP-21's half: the packages that execute a schedule against a running estate. They reach
// the network by definition, which is why the forbidden list cannot apply to them - and why the
// engine list above has to be exact. The boundary is the whole reason two drivers can share one
// model: an engine that could open a socket would eventually contain half a driver, and WP-25 would
// then be written against the other half.
var driver = []string{
	"internal/client",
	"internal/identity",
}

// cmd is a main rather than a library: it reads the model from a path, writes a manifest to one,
// and now also drives. Naming it here rather than discovering it is the point - the exemption is a
// decision somebody recorded.
const exempt = "cmd/"

// imports maps every non-test package in the module to what it imports.
func imports(t *testing.T) map[string][]string {
	t.Helper()
	found := map[string]map[string]bool{}
	fset := token.NewFileSet()

	err := filepath.WalkDir(moduleRoot, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		rel, err := filepath.Rel(moduleRoot, path)
		if err != nil {
			return err
		}
		pkg := filepath.ToSlash(filepath.Dir(rel))

		file, err := parser.ParseFile(fset, path, nil, parser.ImportsOnly)
		if err != nil {
			return err
		}
		if found[pkg] == nil {
			found[pkg] = map[string]bool{}
		}
		for _, imported := range file.Imports {
			unquoted, err := strconv.Unquote(imported.Path.Value)
			if err != nil {
				return err
			}
			found[pkg][unquoted] = true
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walking %s: %v", moduleRoot, err)
	}

	out := map[string][]string{}
	for pkg, set := range found {
		for imported := range set {
			out[pkg] = append(out[pkg], imported)
		}
		sort.Strings(out[pkg])
	}
	return out
}

// reachable is every package an engine package pulls in, following imports within this module.
// Transitive, because a pure package that imports an impure one is impure.
func reachable(graph map[string][]string, from string) map[string]bool {
	seen := map[string]bool{}
	var walk func(string)
	walk = func(pkg string) {
		for _, imported := range graph[pkg] {
			if seen[imported] {
				continue
			}
			seen[imported] = true
			if local, found := strings.CutPrefix(imported, modulePath+"/"); found {
				walk(local)
			}
		}
	}
	walk(from)
	return seen
}

func TestTheEngineReachesNothingOutsideItself(t *testing.T) {
	graph := imports(t)

	for _, pkg := range engine {
		if _, found := graph[pkg]; !found {
			// A rule about a package that no longer exists holds trivially and hides the day
			// somebody renamed the package it was written for.
			t.Fatalf("%s is listed as engine and does not exist", pkg)
		}
		for imported := range reachable(graph, pkg) {
			if reason, banned := forbidden[imported]; banned {
				t.Errorf("%s reaches %s - %s", pkg, imported, reason)
			}
		}
	}
}

func TestEveryPackageIsClassified(t *testing.T) {
	// The control this replaces was "everything that is not cmd/ is pure", which was true while the
	// module held nothing but the engine. Left alone, WP-21's first driver package would have made
	// it fail, and the cheapest way to make it pass again would have been to widen the exemption
	// until it excused everything. A package that is in neither list fails here instead, so the
	// decision is taken deliberately rather than by whoever was in a hurry.
	classified := map[string]bool{}
	for _, pkg := range append(append([]string(nil), engine...), driver...) {
		if classified[pkg] {
			t.Errorf("%s is listed twice", pkg)
		}
		classified[pkg] = true
	}

	for pkg := range imports(t) {
		if strings.HasPrefix(pkg, exempt) || classified[pkg] {
			continue
		}
		t.Errorf("%s is neither engine nor driver - decide which side of the boundary it is on, "+
			"in internal/purity", pkg)
	}
}

func TestTheDriverListNamesPackagesThatExist(t *testing.T) {
	// The same staleness rule the exemption below is held to. A driver entry that outlives its
	// package silently permits I/O in whatever takes that name next.
	graph := imports(t)
	for _, pkg := range driver {
		if _, found := graph[pkg]; !found {
			t.Errorf("%s is listed as driver and does not exist", pkg)
		}
	}
}

func TestTheExemptionIsRealAndNarrow(t *testing.T) {
	// An exemption for a directory that does not exist excuses nothing today and excuses whatever
	// takes that path tomorrow. The same staleness rule as the float allowlist next door.
	graph := imports(t)
	exempted := 0
	for pkg := range graph {
		if strings.HasPrefix(pkg, exempt) {
			exempted++
		}
	}
	if exempted == 0 {
		t.Errorf("nothing lives under %s, so the exemption guards nothing", exempt)
	}
}

func TestTheCheckCatchesAPlantedImport(t *testing.T) {
	// A control nobody has seen fail is a control nobody has tested.
	planted := map[string][]string{
		"internal/arrivals": {modulePath + "/internal/bankday"},
		"internal/bankday":  {"net/http"},
	}
	if !reachable(planted, "internal/arrivals")["net/http"] {
		t.Fatal("an http import two hops away was not reached")
	}
	if reachable(planted, "internal/bankday")["math"] {
		t.Fatal("the walker invented an import nothing declares")
	}
}
