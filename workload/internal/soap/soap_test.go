package soap

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// The three soapAction values contracts/wsdl/customer-master-v1.wsdl declares, transcribed as
// literals. A test that derives them from the code under test proves only that the code agrees with
// itself, which is the same reason mainframe/data/test_comp3.py transcribes its expected bytes.
const (
	getAccountAction  = "http://services.tesserabank.example/customer-master/v1/GetAccount"
	byCustomerAction  = "http://services.tesserabank.example/customer-master/v1/GetAccountsByCustomer"
	notifyPostedActin = "http://services.tesserabank.example/customer-master/v1/NotifyTransferPosted"
)

func TestTheEnvelopeIsSoap11AndCarriesTheOperationInTheServiceNamespace(t *testing.T) {
	body := string(GetAccountRequest("TB00000000000001"))

	for _, want := range []string{
		`<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"`,
		`xmlns:v1="http://services.tesserabank.example/customer-master/v1"`,
		`<v1:GetAccount>`,
		`<v1:accountRef>TB00000000000001</v1:accountRef>`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("the envelope does not carry %q:\n%s", want, body)
		}
	}
}

func TestTheAccountReferenceIsEscapedRatherThanInterpolated(t *testing.T) {
	// No reference this population draws contains a metacharacter, which is exactly why an
	// unescaped writer would survive every test until the day one did.
	body := string(GetAccountRequest("TB<&\"01"))
	if strings.Contains(body, "TB<&") {
		t.Fatalf("the reference was interpolated raw:\n%s", body)
	}
	if !strings.Contains(body, "TB&lt;&amp;") {
		t.Fatalf("the reference was not escaped:\n%s", body)
	}
}

func TestNotifyCarriesATransferAndExactlyTwoMovements(t *testing.T) {
	body := string(NotifyTransferPostedRequest(aTransfer(), aDebitLeg(), aCreditLeg()))

	if got := strings.Count(body, "<v1:movement>"); got != 2 {
		t.Errorf("the request carries %d movements, the schema requires exactly 2:\n%s", got, body)
	}
	for _, want := range []string{
		"<tb:transferRef>TB202603020000000001</tb:transferRef>",
		"<tb:debitAccountRef>TB00000000000001</tb:debitAccountRef>",
		"<tb:creditAccountRef>TB00000000000002</tb:creditAccountRef>",
		"<tb:legNo>1</tb:legNo>",
		"<tb:legNo>2</tb:legNo>",
		"<tb:direction>D</tb:direction>",
		"<tb:direction>C</tb:direction>",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("the request does not carry %q:\n%s", want, body)
		}
	}
}

func TestMoneyCrossesTheWireAsMinorUnitsAndACurrency(t *testing.T) {
	// CLAUDE.md: money is never a floating-point number. 1 234 567.89 is 123456789 and "PLN".
	body := string(NotifyTransferPostedRequest(aTransfer(), aDebitLeg(), aCreditLeg()))

	if !strings.Contains(body, "<tb:amountMinor>123456789</tb:amountMinor>") {
		t.Errorf("the amount is not minor units:\n%s", body)
	}
	if !strings.Contains(body, "<tb:currency>PLN</tb:currency>") {
		t.Errorf("the amount carries no currency:\n%s", body)
	}
	if strings.Contains(body, "1234567.89") || strings.Contains(body, "1.23456789") {
		t.Errorf("a decimal amount reached the wire:\n%s", body)
	}
}

func TestASuccessfulCallReportsTheOperationItPerformed(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("SOAPAction"); got != `"`+getAccountAction+`"` {
			t.Errorf("SOAPAction is %q, expected the WSDL's own value quoted", got)
		}
		if got := r.Header.Get("Content-Type"); !strings.HasPrefix(got, "text/xml") {
			t.Errorf("Content-Type is %q, SOAP 1.1 is text/xml", got)
		}
		w.Header().Set("Content-Type", "text/xml; charset=utf-8")
		_, _ = w.Write([]byte(getAccountResponse))
	}))
	defer server.Close()

	result := New(server.URL, time.Second).Call(context.Background(), GetAccount, GetAccountRequest("TB00000000000001"))

	if result.Outcome != Answered {
		t.Fatalf("outcome %v, error %v", result.Outcome, result.Err)
	}
	if result.Status != http.StatusOK {
		t.Errorf("status %d", result.Status)
	}
	if result.Latency <= 0 {
		t.Errorf("latency %v is not a duration", result.Latency)
	}
}

func TestASoapFaultIsAFaultRatherThanAnAnswer(t *testing.T) {
	// The trap this test exists for: a SOAP 1.1 fault arrives as HTTP 500 with a well-formed body,
	// and a driver that classifies on the status code alone records it as a transport failure - or,
	// worse, a fault returned with 200 as an answer. Both misreport what the endpoint did.
	for _, status := range []int{http.StatusOK, http.StatusInternalServerError} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/xml; charset=utf-8")
			w.WriteHeader(status)
			_, _ = w.Write([]byte(faultResponse))
		}))

		result := New(server.URL, time.Second).Call(context.Background(), GetAccount, GetAccountRequest("TB00000000009999"))
		server.Close()

		if result.Outcome != Faulted {
			t.Errorf("status %d: outcome %v, expected Faulted", status, result.Outcome)
		}
		if result.FaultCode != "ACCT_NOT_FOUND" {
			t.Errorf("status %d: fault code %q", status, result.FaultCode)
		}
	}
}

func TestAFaultCarriesNoIdentityIntoTheReport(t *testing.T) {
	// REQ-DP-001. The endpoint's own tests assert identity never reaches a fault; this asserts the
	// driver would not record it if one ever did. An error path is where personal data escapes.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/xml; charset=utf-8")
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(faultResponse))
	}))
	defer server.Close()

	result := New(server.URL, time.Second).Call(context.Background(), GetAccount, GetAccountRequest("TB00000000009999"))

	if strings.Contains(result.FaultString, "Kowalska") {
		t.Fatalf("the fault string was recorded verbatim: %q", result.FaultString)
	}
}

func TestAnUnreachableEndpointIsUnknownRatherThanRefused(t *testing.T) {
	// WP-21's classification, kept: a request that never got an answer may or may not have been
	// applied, and calling it a refusal asserts something the driver cannot know.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	endpoint := server.URL
	server.Close()

	result := New(endpoint, 200*time.Millisecond).Call(context.Background(), GetAccount, GetAccountRequest("TB00000000000001"))

	if result.Outcome != Unknown {
		t.Fatalf("outcome %v, expected Unknown", result.Outcome)
	}
	if result.Err == nil {
		t.Error("an unknown outcome carries no reason")
	}
}

func TestATimeoutIsUnknownAndDoesNotHangTheRun(t *testing.T) {
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release
	}))
	defer func() { close(release); server.Close() }()

	result := New(server.URL, 100*time.Millisecond).Call(context.Background(), GetAccount, GetAccountRequest("TB00000000000001"))

	if result.Outcome != Unknown {
		t.Fatalf("outcome %v, expected Unknown", result.Outcome)
	}
}

func TestTheThreeOperationsCarryTheActionsTheWsdlDeclares(t *testing.T) {
	for operation, want := range map[Operation]string{
		GetAccount:            getAccountAction,
		GetAccountsByCustomer: byCustomerAction,
		NotifyTransferPosted:  notifyPostedActin,
	} {
		if got := operation.Action(); got != want {
			t.Errorf("%v action is %q, the WSDL declares %q", operation, got, want)
		}
	}
}

// --- fixtures -------------------------------------------------------------------------------

func aTransfer() Transfer {
	return Transfer{
		TransferRef:      "TB202603020000000001",
		DebitAccountRef:  "TB00000000000001",
		CreditAccountRef: "TB00000000000002",
		AmountMinor:      123456789,
		Currency:         "PLN",
		Status:           "POSTED",
		Reference:        "WORKLOAD SOAP DRIVER",
		RequestedAt:      "2026-03-02T09:15:00Z",
		PostedAt:         "2026-03-02T09:15:00Z",
		CorrelationID:    "11111111-1111-4111-8111-111111111111",
	}
}

func aDebitLeg() Movement {
	return Movement{
		MovementRef: "MV20260302000000000001",
		TransferRef: "TB202603020000000001",
		LegNo:       1,
		AccountRef:  "TB00000000000001",
		Direction:   "D",
		AmountMinor: 123456789,
		Currency:    "PLN",
		ValueDate:   "2026-03-02",
		PostedAt:    "2026-03-02T09:15:00Z",
	}
}

func aCreditLeg() Movement {
	leg := aDebitLeg()
	leg.MovementRef = "MV20260302000000000002"
	leg.LegNo = 2
	leg.AccountRef = "TB00000000000002"
	leg.Direction = "C"
	return leg
}

const getAccountResponse = `<?xml version="1.0"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
  <soapenv:Body>
    <ns2:GetAccountResponse xmlns:ns2="http://services.tesserabank.example/customer-master/v1">
      <account xmlns="http://schemas.tesserabank.example/canonical/v1">
        <accountRef>TB00000000000001</accountRef>
      </account>
    </ns2:GetAccountResponse>
  </soapenv:Body>
</soapenv:Envelope>`

// A fault carrying a name in its faultstring, which the real endpoint never does - the driver is
// asserted not to record one even if it did.
const faultResponse = `<?xml version="1.0"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
  <soapenv:Body>
    <soapenv:Fault>
      <faultcode>soapenv:Server</faultcode>
      <faultstring>no account for Kowalska</faultstring>
      <detail>
        <ServiceFault xmlns="http://services.tesserabank.example/customer-master/v1">
          <faultCode>ACCT_NOT_FOUND</faultCode>
          <faultMessage>no account with that reference</faultMessage>
        </ServiceFault>
      </detail>
    </soapenv:Fault>
  </soapenv:Body>
</soapenv:Envelope>`
