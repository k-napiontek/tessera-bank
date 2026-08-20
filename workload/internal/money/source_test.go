package money_test

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The money rule, enforced against the source rather than asserted about it.
//
// batch/reporting/money.py and edge/web-banking/src/money.source.test.ts have tests of exactly this
// shape, for exactly this reason: a comment saying "no floats here" is worth nothing the day
// somebody divides by 100 to render a figure. Go has one advantage over both - a parser in the
// standard library - so this scanner works on an AST rather than on stripped text, and cannot
// mistake a "/" inside a string for a division.
//
// The rule is not "no float64 anywhere". An arrival process and an intensity curve are continuous
// mathematics and a float is the right type for both. The rule is that a float may appear only in a
// file that has declared why, and that no float ever touches an amount. Both halves are checked.

const moduleRoot = "../.."

// Files permitted to name a floating-point type, each with the reason it needs one. A file absent
// from this map fails the moment it mentions float64 - which is the point: adding a float becomes a
// decision somebody records here rather than a keystroke nobody notices.
//
// An entry arrives in the same commit as the file it excuses, and TestEveryJustifiedFileStillExists
// removes it again if that file ever goes away.
var floatIsJustified = map[string]string{
	"internal/bankday/curve.go":         "an intensity is events per second at an instant, and the shape it comes from is a ratio between hours - neither is a count and neither is money",
	"internal/arrivals/arrivals.go":     "a Poisson process is defined over continuous time - an interarrival gap is a real number of seconds and thinning compares an intensity ratio against a uniform draw",
	"internal/population/population.go": "the log-normal amount draw and the weighted picks - a distribution is continuous. The float ends at drawMinor, which hands back int64 minor units and nothing else",
}

// Calls that have no business anywhere in this module, whatever they are applied to.
var forbiddenAnywhere = map[string]string{
	"strconv.ParseFloat":  "parse minor units with strconv.ParseInt",
	"strconv.FormatFloat": "render minor units with strconv.FormatInt",
	"time.Now":            "the engine never reads the wall clock - that is what makes a run reproducible",
}

// Calls that are ordinary arithmetic on anything else and are a lost figure on an amount. Rounding
// a headcount is fine; rounding money is the division that already lost it.
var forbiddenOnAmounts = map[string]string{
	"math.Round": "rounding an amount is the division that lost it",
	"math.Floor": "rounding an amount is the division that lost it",
	"math.Ceil":  "rounding an amount is the division that lost it",
}

// The single place in this module where a float is allowed to meet money, keyed by file and
// function. A log-normal draw needs its median as a real number, and the conversion back to int64
// has to happen somewhere; what matters is that it happens in one named function that somebody
// chose, rather than wherever it was convenient.
//
// An entry arrives in the same commit as the function it excuses, and
// TestEveryMoneyExemptionStillNamesARealFunction removes it again if that function is renamed.
var floatMeetsMoneyIn = map[string]string{
	"internal/population/population.go#drawMinor": "a log-normal draw needs its median as a real number; drawMinor converts back to int64 and clamps before anything leaves it",
}

type finding struct {
	where string
	what  string
}

// scan applies every rule to one parsed file.
func scan(fset *token.FileSet, file *ast.File, rel string, source []byte, justified, exempt map[string]string) []finding {
	var found []finding
	at := func(pos token.Pos) string {
		p := fset.Position(pos)
		return fmt.Sprintf("%s:%d", rel, p.Line)
	}
	text := func(node ast.Node) string {
		p := fset.Position(node.Pos())
		e := fset.Position(node.End())
		return string(source[p.Offset:e.Offset])
	}

	inMoneyPackage := strings.HasPrefix(rel, "internal/money/")

	// inspect walks one declaration, knowing which function it is inside. The enclosing function is
	// what scopes the one exemption in this file: a float may meet money in a function somebody
	// named, and nowhere else.
	inspect := func(node ast.Node, function string) {
		mayMixFloatAndMoney := false
		if function != "" {
			_, mayMixFloatAndMoney = exempt[rel+"#"+function]
		}

		ast.Inspect(node, func(node ast.Node) bool {
			switch n := node.(type) {

			case *ast.Ident:
				if n.Name == "float64" || n.Name == "float32" {
					if _, allowed := justified[rel]; !allowed {
						found = append(found, finding{at(n.Pos()),
							"names " + n.Name + ", and no reason for it is recorded in floatIsJustified"})
					}
				}

			case *ast.BasicLit:
				if n.Kind == token.FLOAT && inMoneyPackage {
					found = append(found, finding{at(n.Pos()), "a fractional literal " + n.Value})
				}

			case *ast.BinaryExpr:
				if inMoneyPackage && (n.Op == token.QUO || n.Op == token.MUL) {
					found = append(found, finding{at(n.OpPos),
						"the operator " + n.Op.String() + " in " + text(n)})
				}

			case *ast.CallExpr:
				switch fn := n.Fun.(type) {

				case *ast.Ident:
					// A conversion: float64(x) applied to something holding an amount.
					if fn.Name == "float64" || fn.Name == "float32" {
						for _, arg := range n.Args {
							if touchesAnAmount(text(arg)) && !mayMixFloatAndMoney {
								found = append(found, finding{at(n.Pos()),
									"converts an amount to " + fn.Name + " in " + describe(function) +
										", which is not in floatMeetsMoneyIn: " + text(n)})
							}
						}
					}

				case *ast.SelectorExpr:
					pkg, ok := fn.X.(*ast.Ident)
					if !ok {
						return true
					}
					name := pkg.Name + "." + fn.Sel.Name
					if advice, forbidden := forbiddenAnywhere[name]; forbidden {
						found = append(found, finding{at(n.Pos()), "calls " + name + " - " + advice})
					}
					if advice, forbidden := forbiddenOnAmounts[name]; forbidden {
						for _, arg := range n.Args {
							if touchesAnAmount(text(arg)) {
								found = append(found, finding{at(n.Pos()),
									"calls " + name + " on " + text(arg) + " - " + advice})
							}
						}
					}
				}
			}
			return true
		})
	}

	for _, decl := range file.Decls {
		if function, ok := decl.(*ast.FuncDecl); ok {
			inspect(function, function.Name.Name)
			continue
		}
		inspect(decl, "")
	}
	return found
}

func describe(function string) string {
	if function == "" {
		return "a declaration outside any function"
	}
	return function
}

// touchesAnAmount reports whether an expression's source names money. Deliberately crude and
// deliberately broad: a false positive costs somebody a rename, and a false negative costs the bank
// a rounded figure that reconciles to nothing.
func touchesAnAmount(expr string) bool {
	for _, marker := range []string{"Minor", "minor", "Amount", "amount"} {
		if strings.Contains(expr, marker) {
			return true
		}
	}
	return false
}

// production yields every non-test Go file in the module, relative to its root.
func production(t *testing.T) []string {
	t.Helper()
	var files []string
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
		files = append(files, filepath.ToSlash(rel))
		return nil
	})
	if err != nil {
		t.Fatalf("walking %s: %v", moduleRoot, err)
	}
	if len(files) == 0 {
		t.Fatal("found no Go source at all - the scanner is looking in the wrong place")
	}
	return files
}

func TestNoFloatReachesAnAmount(t *testing.T) {
	fset := token.NewFileSet()
	var findings []finding

	for _, rel := range production(t) {
		path := filepath.Join(moduleRoot, rel)
		file, err := parser.ParseFile(fset, path, nil, parser.SkipObjectResolution)
		if err != nil {
			t.Fatalf("parsing %s: %v", rel, err)
		}
		source, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("reading %s: %v", rel, err)
		}
		findings = append(findings, scan(fset, file, rel, source, floatIsJustified, floatMeetsMoneyIn)...)
	}

	for _, f := range findings {
		t.Errorf("%s  %s", f.where, f.what)
	}
}

func TestEveryJustifiedFileStillExists(t *testing.T) {
	// An allowlist that outlives the file it excused is an allowlist that silently excuses the next
	// file to take that path. The same failure as a stale suppression in any other linter.
	present := map[string]bool{}
	for _, rel := range production(t) {
		present[rel] = true
	}
	for rel := range floatIsJustified {
		if !present[rel] {
			t.Errorf("floatIsJustified names %s, which no longer exists", rel)
		}
	}
}

func TestTheScannerCatchesAPlantedFault(t *testing.T) {
	// A control nobody has seen fail is a control nobody has tested. Each of these is a mistake
	// somebody would actually make, and each must be caught.
	cases := []struct {
		name   string
		rel    string
		source string
	}{
		{
			name:   "dividing minor units to render a figure",
			rel:    "internal/money/money.go",
			source: "package money\nfunc show(minor int64) int64 { return minor / 100 }\n",
		},
		{
			name:   "a fractional literal in the money package",
			rel:    "internal/money/money.go",
			source: "package money\nvar rate = 1.5\n",
		},
		{
			name:   "converting an amount to a float somewhere else",
			rel:    "internal/population/population.go",
			source: "package population\nfunc scale(minorUnits int64) float64 { return float64(minorUnits) * 2 }\n",
		},
		{
			name:   "reaching for strconv.ParseFloat",
			rel:    "internal/manifest/manifest.go",
			source: "package manifest\nimport \"strconv\"\nfunc read(s string) { _, _ = strconv.ParseFloat(s, 64) }\n",
		},
		{
			name:   "a new file that quietly takes a float64",
			rel:    "internal/schedule/schedule.go",
			source: "package schedule\nvar drift float64\n",
		},
		{
			name: "the same conversion in a function nobody named",
			rel:  "internal/population/population.go",
			source: "package population\nimport \"math\"\n" +
				"func elsewhere(medianMinor int64) int64 { return int64(math.Exp(float64(medianMinor))) }\n",
		},
		{
			name:   "the exempt function name reused in another file",
			rel:    "internal/plan/plan.go",
			source: "package plan\nfunc drawMinor(minor int64) float64 { return float64(minor) }\n",
		},
		{
			name:   "reading the wall clock",
			rel:    "internal/arrivals/arrivals.go",
			source: "package arrivals\nimport \"time\"\nfunc start() { _ = time.Now() }\n",
		},
		{
			name: "rounding an amount",
			rel:  "internal/population/population.go",
			source: "package population\nimport \"math\"\n" +
				"func settle(amount float64) int64 { return int64(math.Round(amount)) }\n",
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fset := token.NewFileSet()
			file, err := parser.ParseFile(fset, c.rel, c.source, parser.SkipObjectResolution)
			if err != nil {
				t.Fatalf("parsing the planted source: %v", err)
			}
			if got := scan(fset, file, c.rel, []byte(c.source), floatIsJustified, floatMeetsMoneyIn); len(got) == 0 {
				t.Fatalf("the scanner passed %q, which it must refuse", c.source)
			}
		})
	}
}

func TestTheScannerAllowsAJustifiedFloat(t *testing.T) {
	// The other half: a scanner that refuses everything is not a control either. An intensity curve
	// is continuous mathematics and a float is the right type for it, so a file that has recorded
	// why must pass - including with a fractional literal in it, which only the money package bans.
	const rel = "internal/bankday/curve.go"
	fset := token.NewFileSet()
	source := "package bankday\nfunc shape(hour int) float64 { return 1.6 }\n"
	file, err := parser.ParseFile(fset, rel, source, parser.SkipObjectResolution)
	if err != nil {
		t.Fatalf("parsing: %v", err)
	}
	allowed := map[string]string{rel: "the diurnal curve is a shape, not a count"}
	exempt := map[string]string{}
	if got := scan(fset, file, rel, []byte(source), allowed, exempt); len(got) != 0 {
		t.Fatalf("a justified float was refused: %v", got)
	}
	// The same source without the entry is refused, so it is the allowlist that decides rather than
	// something incidental about the file.
	if got := scan(fset, file, rel, []byte(source), map[string]string{}, map[string]string{}); len(got) == 0 {
		t.Fatal("an unjustified float was allowed")
	}
}

func TestTheExemptFunctionMayMixFloatAndMoney(t *testing.T) {
	// The other half again. drawMinor is the one function allowed to convert an amount, and it has
	// to actually be allowed - an exemption that refuses its own subject is not an exemption.
	const rel = "internal/population/population.go"
	source := "package population\nimport \"math\"\n" +
		"func drawMinor(medianMinor int64) int64 { return int64(math.Exp(float64(medianMinor))) }\n"
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, rel, source, parser.SkipObjectResolution)
	if err != nil {
		t.Fatalf("parsing: %v", err)
	}
	allowed := map[string]string{rel: "the log-normal draw"}
	exempt := map[string]string{rel + "#drawMinor": "the log-normal draw"}
	if got := scan(fset, file, rel, []byte(source), allowed, exempt); len(got) != 0 {
		t.Fatalf("the exempt function was refused: %v", got)
	}
	// Without the entry the same source is refused, so it is the exemption that decides rather
	// than something incidental about the function.
	if got := scan(fset, file, rel, []byte(source), allowed, map[string]string{}); len(got) == 0 {
		t.Fatal("an unexempted conversion of an amount was allowed")
	}
}

func TestEveryMoneyExemptionStillNamesARealFunction(t *testing.T) {
	// The same staleness rule as the file allowlist. An exemption keyed on a function that has been
	// renamed silently stops exempting anything, and - worse - starts exempting nothing while
	// looking as though it still guards something.
	fset := token.NewFileSet()
	for key := range floatMeetsMoneyIn {
		rel, function, found := strings.Cut(key, "#")
		if !found {
			t.Errorf("floatMeetsMoneyIn key %q is not file#function", key)
			continue
		}
		file, err := parser.ParseFile(fset, filepath.Join(moduleRoot, rel), nil, parser.SkipObjectResolution)
		if err != nil {
			t.Errorf("floatMeetsMoneyIn names %s, which will not parse: %v", rel, err)
			continue
		}
		declared := false
		for _, decl := range file.Decls {
			if fn, ok := decl.(*ast.FuncDecl); ok && fn.Name.Name == function {
				declared = true
				break
			}
		}
		if !declared {
			t.Errorf("floatMeetsMoneyIn names %s, and %s declares no such function", key, rel)
		}
	}
}
