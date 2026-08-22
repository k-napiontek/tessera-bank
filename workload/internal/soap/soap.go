// Package soap drives legacy/customer-master over the wire it actually speaks.
//
// **A driver, not the engine.** It opens sockets, so internal/purity classifies it beside
// internal/client rather than beside internal/model. WP-21 drives the modern spine over REST; this
// is the same idea one stratum down, against a JAX-WS endpoint on Tomcat 8.5.
//
// SOAP 1.1, document/literal, because that is what contracts/wsdl/customer-master-v1.wsdl binds:
// text/xml, a quoted SOAPAction header, and a fault that arrives as a well-formed body which may
// carry any status code at all. The envelope is written rather than generated - the module is
// standard library only, and encoding/xml cannot express a document/literal envelope without a
// struct per operation that would restate the contract in Go.
//
// Two classifications are load-bearing and both are WP-21's, kept deliberately:
//
//   - a **fault is not a transport failure**. The endpoint answered, and it answered with a refusal
//     it is entitled to give. A driver that reads the status code alone records ACCT_NOT_FOUND as a
//     broken server, and the run then describes the driver.
//   - anything with no answer is **unknown**, never failed. The request may well have been applied,
//     which is the case WP-14 found live and WP-21's driver was built around.
package soap

import (
	"bytes"
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// The two namespaces the WSDL binds. Written here as the constants they are, because every envelope
// in this file carries both and a typo in one would produce a request the endpoint rejects with a
// message about something else.
const (
	serviceNS   = "http://services.tesserabank.example/customer-master/v1"
	canonicalNS = "http://schemas.tesserabank.example/canonical/v1"
	envelopeNS  = "http://schemas.xmlsoap.org/soap/envelope/"
)

// Operation is one of the three the WSDL declares.
type Operation int

const (
	GetAccount Operation = iota
	GetAccountsByCustomer
	NotifyTransferPosted
)

// Action is the soapAction the WSDL binds to this operation. Tomcat routes on it, so a wrong value
// is a request that reaches the servlet and never reaches the method.
func (o Operation) Action() string {
	return serviceNS + "/" + o.String()
}

func (o Operation) String() string {
	switch o {
	case GetAccount:
		return "GetAccount"
	case GetAccountsByCustomer:
		return "GetAccountsByCustomer"
	case NotifyTransferPosted:
		return "NotifyTransferPosted"
	}
	return "unknown"
}

// Outcome is what the endpoint did, in the four terms a report can use.
type Outcome int

const (
	// Answered: the endpoint returned the response the operation declares.
	Answered Outcome = iota
	// Faulted: the endpoint returned a ServiceFault. It answered; the answer was a refusal.
	Faulted
	// Rejected: an HTTP status with no SOAP body at all - a 404 at the wrong path, a 401, a 503
	// from something in front of Tomcat. The endpoint did not answer and did not fault.
	Rejected
	// Unknown: no answer arrived. It may or may not have been applied.
	Unknown
)

func (o Outcome) String() string {
	switch o {
	case Answered:
		return "answered"
	case Faulted:
		return "faulted"
	case Rejected:
		return "rejected"
	case Unknown:
		return "unknown"
	}
	return "unclassified"
}

// Result is one call, as a report can read it.
type Result struct {
	Operation Operation
	Outcome   Outcome
	Status    int
	Latency   time.Duration

	// FaultCode is the ServiceFault's own code - ACCT_NOT_FOUND and the like. It is a closed set the
	// contract declares, which is why it is safe to record where FaultString is not.
	FaultCode string
	// FaultString is the human-readable half, and it is deliberately **not** recorded verbatim. The
	// contract says a fault must never carry personal data and the endpoint's tests assert it; this
	// driver does not rely on that holding, because an error path is where such a leak would first
	// appear. Only the length is kept, which is enough to notice one changing.
	FaultString string

	Err error
}

// Client calls one endpoint.
type Client struct {
	endpoint string
	http     *http.Client
}

// New builds a client with a per-call timeout. The timeout is what keeps a stalled Tomcat from
// becoming a stalled run: a driver that waits forever measures its own patience.
func New(endpoint string, timeout time.Duration) *Client {
	return &Client{
		endpoint: endpoint,
		http: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				MaxIdleConns:        256,
				MaxIdleConnsPerHost: 256,
				MaxConnsPerHost:     0,
			},
		},
	}
}

// Call sends one envelope and classifies what came back.
func (c *Client) Call(ctx context.Context, operation Operation, envelope []byte) Result {
	started := time.Now()
	result := Result{Operation: operation}

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(envelope))
	if err != nil {
		result.Outcome = Unknown
		result.Err = err
		result.Latency = time.Since(started)
		return result
	}
	request.Header.Set("Content-Type", "text/xml; charset=utf-8")
	// Quoted, per SOAP 1.1. An unquoted value is accepted by some stacks and ignored by others,
	// which is the worst of the three possible behaviours.
	request.Header.Set("SOAPAction", `"`+operation.Action()+`"`)

	response, err := c.http.Do(request)
	if err != nil {
		result.Outcome = Unknown
		result.Err = err
		result.Latency = time.Since(started)
		return result
	}
	defer func() {
		_, _ = io.Copy(io.Discard, response.Body)
		_ = response.Body.Close()
	}()

	body, err := io.ReadAll(response.Body)
	result.Latency = time.Since(started)
	result.Status = response.StatusCode
	if err != nil {
		result.Outcome = Unknown
		result.Err = err
		return result
	}

	// The classification, in the order that matters. A fault is looked for first and at any status,
	// because SOAP 1.1 sends one with 500 and some stacks send one with 200.
	if code, message, ok := parseFault(body); ok {
		result.Outcome = Faulted
		result.FaultCode = code
		result.FaultString = redact(message)
		return result
	}
	if response.StatusCode != http.StatusOK {
		result.Outcome = Rejected
		result.Err = fmt.Errorf("soap: status %d with no fault body", response.StatusCode)
		return result
	}
	result.Outcome = Answered
	return result
}

// redact keeps the shape of a fault message and not its content. The contract forbids personal data
// in a fault and the endpoint's own tests assert it; recording the string anyway would put this
// driver's report one endpoint bug away from carrying a name.
func redact(message string) string {
	trimmed := strings.TrimSpace(message)
	if trimmed == "" {
		return ""
	}
	return fmt.Sprintf("<%d chars, not recorded>", len(trimmed))
}

// fault is the SOAP 1.1 fault, and the ServiceFault the WSDL declares inside its detail.
type fault struct {
	XMLName xml.Name `xml:"Envelope"`
	Body    struct {
		Fault *struct {
			Code   string `xml:"faultcode"`
			String string `xml:"faultstring"`
			Detail struct {
				Service *struct {
					Code    string `xml:"faultCode"`
					Message string `xml:"faultMessage"`
				} `xml:"ServiceFault"`
			} `xml:"detail"`
		} `xml:"Fault"`
	} `xml:"Body"`
}

func parseFault(body []byte) (code string, message string, ok bool) {
	var envelope fault
	if err := xml.Unmarshal(body, &envelope); err != nil {
		return "", "", false
	}
	if envelope.Body.Fault == nil {
		return "", "", false
	}
	if service := envelope.Body.Fault.Detail.Service; service != nil {
		message = service.Message
		if message == "" {
			message = envelope.Body.Fault.String
		}
		return service.Code, message, true
	}
	// A fault with no ServiceFault detail is still a fault - a stack trace from the container, most
	// likely - and reporting it as an answer would be the mistake this whole branch exists to avoid.
	return envelope.Body.Fault.Code, envelope.Body.Fault.String, true
}
