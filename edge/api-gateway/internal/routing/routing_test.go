package routing_test

import (
	"bufio"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/correlation"
	"github.com/k-napiontek/tessera-bank/edge/api-gateway/internal/routing"
)

const contract = "../../../../contracts/openapi/ledger-core.yaml"

func TestTheTableCoversTheContractExactly(t *testing.T) {
	declared := operationsInContract(t)

	routed := map[string]bool{}
	for _, route := range routing.Routes() {
		key := route.Method + " " + route.Path
		if routed[key] {
			t.Errorf("%s is routed twice", key)
		}
		routed[key] = true
	}

	// A route the gateway does not know about is an operation no customer can reach; a route the
	// contract does not declare is a hole opened at the edge for something that may not exist.
	for key := range declared {
		if !routed[key] {
			t.Errorf("%s is declared in the contract and not routed", key)
		}
	}
	for key := range routed {
		if !declared[key] {
			t.Errorf("%s is routed and not declared in the contract", key)
		}
	}
}

func TestEveryRouteCarriesAScopeAndAClass(t *testing.T) {
	classes := map[string]bool{}
	for _, route := range routing.Routes() {
		if route.Scope == "" {
			t.Errorf("%s %s requires no scope", route.Method, route.Path)
		}
		if route.Class == "" {
			t.Errorf("%s %s has no class", route.Method, route.Path)
		}
		if classes[route.Class] {
			t.Errorf("two routes share the class %q", route.Class)
		}
		classes[route.Class] = true
	}
}

func TestReadingNeverRequiresAWriteScope(t *testing.T) {
	for _, route := range routing.Routes() {
		if route.Method == http.MethodGet && strings.Contains(route.Scope, "write") {
			t.Errorf("%s %s is a read and demands %q", route.Method, route.Path, route.Scope)
		}
		if route.Method == http.MethodPost && route.Scope == routing.ScopeRead {
			t.Errorf("%s %s moves state and demands only the read scope", route.Method, route.Path)
		}
	}
}

func match(t *testing.T, method, path string) (*httptest.ResponseRecorder, *routing.Route) {
	t.Helper()

	var matched *routing.Route
	handler := correlation.Middleware(routing.Middleware(routing.Routes())(http.HandlerFunc(
		func(_ http.ResponseWriter, r *http.Request) {
			if route, ok := routing.FromContext(r.Context()); ok {
				matched = &route
			}
		})))

	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(method, path, nil))
	return recorder, matched
}

func TestAKnownPathIsMatchedWithItsRoute(t *testing.T) {
	cases := map[string]struct{ method, path, class string }{
		"open an account":  {http.MethodPost, "/accounts", "accounts.open"},
		"read an account":  {http.MethodGet, "/accounts/ACC-000001", "accounts.get"},
		"read a balance":   {http.MethodGet, "/accounts/ACC-000001/balance", "accounts.balance"},
		"list holds":       {http.MethodGet, "/accounts/ACC-000001/holds", "holds.list"},
		"place a hold":     {http.MethodPost, "/accounts/ACC-000001/holds", "holds.place"},
		"create transfer":  {http.MethodPost, "/transfers", "transfers.create"},
		"reverse transfer": {http.MethodPost, "/transfers/TRF-000001/reversals", "transfers.reverse"},
		"capture a hold":   {http.MethodPost, "/holds/HLD-000001/capture", "holds.capture"},
	}
	for name, c := range cases {
		response, route := match(t, c.method, c.path)
		if route == nil {
			t.Errorf("%s: %s %s did not match, status %d", name, c.method, c.path, response.Code)
			continue
		}
		if route.Class != c.class {
			t.Errorf("%s: class = %q, want %q", name, route.Class, c.class)
		}
	}
}

func TestAnUnknownPathIsRefusedAtTheEdge(t *testing.T) {
	for _, path := range []string{
		"/",
		"/actuator/prometheus",
		"/accounts/ACC-000001/holds/HLD-1/anything",
		"/transfers/TRF-1/reversals/extra",
		"/admin",
	} {
		response, route := match(t, http.MethodGet, path)

		if route != nil {
			t.Errorf("%s matched a route", path)
		}
		// The ledger's actuator endpoints are the reason this matters: they are reachable on the
		// same service and expose metrics and health detail no customer should see. A gateway that
		// forwards whatever it does not recognise publishes them.
		if response.Code != http.StatusNotFound {
			t.Errorf("%s: status = %d, want 404", path, response.Code)
		}
		if got := response.Header().Get("Content-Type"); got != "application/problem+json" {
			t.Errorf("%s: content type = %q", path, got)
		}
	}
}

func TestAKnownPathWithTheWrongMethodIsRefusedWithTheAllowedOnes(t *testing.T) {
	response, route := match(t, http.MethodDelete, "/accounts/ACC-000001/holds")

	if route != nil {
		t.Fatal("DELETE matched a route")
	}
	if response.Code != http.StatusMethodNotAllowed {
		t.Errorf("status = %d, want 405", response.Code)
	}
	allow := response.Header().Get("Allow")
	// RFC 9110 requires the header on a 405, and a client that reads it learns what the resource
	// does support without a second request.
	if !strings.Contains(allow, http.MethodGet) || !strings.Contains(allow, http.MethodPost) {
		t.Errorf("Allow = %q, want both GET and POST", allow)
	}
}

func TestTrailingSlashesDoNotOpenASecondSpelling(t *testing.T) {
	// One resource, one path. A gateway that treats /transfers and /transfers/ as the same route
	// gives the rate limiter and the metrics two names for one thing.
	if response, route := match(t, http.MethodPost, "/transfers/"); route != nil {
		t.Errorf("/transfers/ matched, status %d", response.Code)
	}
}

func TestAPathSegmentIsNotAllowedToBeEmpty(t *testing.T) {
	if _, route := match(t, http.MethodGet, "/accounts//balance"); route != nil {
		t.Error("an empty account reference matched a route")
	}
}

// operationsInContract reads the method and path of every operation the OpenAPI document declares.
//
// It is a scanner rather than a YAML parser on purpose: pulling in a YAML library would add a third
// dependency to a component whose constraint is standard library first, and the document's validity
// is already proven by contracts/validate.sh. This only needs the inventory, so it reads the two
// indentation levels that carry it and fails loudly if the file does not have the shape it expects.
func operationsInContract(t *testing.T) map[string]bool {
	t.Helper()

	file, err := os.Open(contract)
	if err != nil {
		t.Fatalf("the contract must be readable from the gateway's tests: %v", err)
	}
	defer file.Close()

	methods := map[string]bool{"get": true, "post": true, "put": true, "patch": true, "delete": true}
	operations := map[string]bool{}

	inPaths := false
	path := ""
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "\t") {
			t.Fatalf("the contract contains a tab, which YAML forbids: %q", line)
		}
		switch {
		case line == "paths:":
			inPaths = true
		case !inPaths:
			continue
		case strings.HasPrefix(line, "  /"):
			path = strings.TrimSuffix(strings.TrimSpace(line), ":")
		case strings.HasPrefix(line, "    ") && !strings.HasPrefix(line, "     "):
			word := strings.TrimSuffix(strings.TrimSpace(line), ":")
			if methods[word] && path != "" {
				operations[strings.ToUpper(word)+" "+path] = true
			}
		case line != "" && !strings.HasPrefix(line, " "):
			// A new top-level key ends the paths block.
			inPaths = false
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("read the contract: %v", err)
	}
	if len(operations) == 0 {
		t.Fatal("no operations were found in the contract; the scanner no longer understands it")
	}
	return operations
}
