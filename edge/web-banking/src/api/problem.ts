/**
 * RFC 9457 Problem Details, read from two producers that do not share a contract.
 *
 * `contracts/openapi/ledger-core.yaml` declares the shape and the ledger's nine problem types. The
 * gateway emits eight more of its own - `unauthenticated`, `forbidden`, `rate-limited`,
 * `payload-too-large`, `no-route`, `upstream-timeout`, `upstream-unusable`, `upstream-oversized` -
 * which follow-up F-34 records as being in no contract at all. This module is the second consumer
 * to need them, which is the argument for writing that contract rather than a reason to guess.
 *
 * `type` is the stable identifier; `title` and `detail` are prose the producers may reword without
 * a contract change. So the wording a customer sees is keyed on `type` and falls back to `title` -
 * never to a bare status code, which tells a customer nothing and a support engineer little more.
 */

export const PROBLEM_NAMESPACE = 'https://problems.tesserabank.example/';

export interface Violation {
  readonly field: string;
  readonly message: string;
}

export interface Problem {
  /** Full `type` URI as it arrived, or '' when the body was not a problem document. */
  readonly type: string;
  /** The last segment of `type` - what the code branches on. '' when there is none. */
  readonly slug: string;
  readonly title: string;
  readonly status: number;
  readonly detail: string | undefined;
  readonly correlationId: string | undefined;
  readonly violations: readonly Violation[];
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function readViolations(value: unknown): readonly Violation[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const violations: Violation[] = [];
  for (const entry of value) {
    const record = asRecord(entry);
    const field = asString(record['field']);
    const message = asString(record['message']);
    if (field !== undefined && message !== undefined) {
      violations.push({ field, message });
    }
  }
  return violations;
}

/**
 * Read a problem document out of a response body.
 *
 * The body is whatever arrived. A gateway timeout served by infrastructure the estate does not own
 * is HTML, and a screen still has to say something honest about it, so nothing here assumes the
 * shape it hopes for.
 */
export function parseProblem(body: unknown, status: number): Problem {
  const record = asRecord(body);
  const type = asString(record['type']) ?? '';
  const slug = type.startsWith(PROBLEM_NAMESPACE) ? type.slice(PROBLEM_NAMESPACE.length) : '';
  const declaredStatus = record['status'];
  return {
    type,
    slug,
    title: asString(record['title']) ?? '',
    status: typeof declaredStatus === 'number' ? declaredStatus : status,
    detail: asString(record['detail']),
    correlationId: asString(record['correlationId']),
    violations: readViolations(record['violations']),
  };
}

/**
 * What the customer is told.
 *
 * Deliberately plain, and deliberately without the type URI, the status code or the correlation id
 * - the first two mean nothing to the person reading, and the third belongs beside a "contact us"
 * affordance rather than inside the sentence explaining what happened.
 */
const WORDING: Readonly<Record<string, string>> = Object.freeze({
  // The ledger's own.
  'insufficient-funds': 'There is not enough available balance on that account to cover this.',
  'currency-mismatch': 'Both accounts must be in the same currency. This estate performs no conversion.',
  'not-found': 'We could not find that account or transfer.',
  'validation-failed': 'Some of the details are not valid. Check the highlighted fields.',
  'idempotency-conflict': 'This looks like a different transfer sent under the same reference. Start it again.',
  'conflicting-state': 'That has already been done, or it can no longer be done.',
  'account-already-open': 'That account reference is already in use.',
  'not-actionable': 'We understood the request but cannot carry it out as it stands.',
  // Never "nothing was changed" - that is a claim about the ledger this application cannot make.
  internal: 'Something went wrong at our end, and we do not yet know whether this went through.',
  // The gateway's own - F-34.
  unauthenticated: 'Your session is not valid any more. Sign in again.',
  forbidden: 'This account is not one you are allowed to see.',
  'rate-limited': 'Too many requests just now. Wait a moment and try again.',
  'payload-too-large': 'That request was too large to send.',
  'no-route': 'That is not something this service offers.',
  'upstream-timeout': 'The bank did not answer in time. Your request may or may not have gone through.',
  'upstream-unusable': 'We could not reach the bank. Your request may or may not have gone through.',
  'upstream-oversized': 'The answer was too large to show.',
});

export function describeProblem(problem: Problem): string {
  const known = WORDING[problem.slug];
  if (known !== undefined) {
    return known;
  }
  if (problem.title !== '') {
    return problem.title;
  }
  return 'Something went wrong, and we could not tell what.';
}

/**
 * True when the outcome of a money-moving request is genuinely unknown to the client.
 *
 * **The line is 4xx against 5xx, not a list of problem types.** A 4xx is decided before any money
 * moves - validation, funds, a conflicting state, an expired token - so it is the only class of
 * answer a client may present to a customer as "not sent". A 5xx is not an answer about the
 * ledger at all: the request may have committed and lost its response on the way back, and
 * anything standing between the customer and the ledger - a reverse proxy, a load balancer, the
 * gateway itself - can produce one carrying no problem type this application has ever heard of.
 *
 * An earlier version listed `upstream-timeout` and `upstream-unusable` and nothing else. Stopping
 * the gateway mid-submission during the live walkthrough produced a bare `500` from the proxy in
 * front of it, and the screen said **"Not sent"** about a transfer that may well have posted -
 * which is precisely the statement WP-14's constraints forbid, and precisely the one that gets a
 * customer to send their money a second time.
 */
export function isOutcomeUnknown(problem: Problem): boolean {
  return problem.status >= 500;
}
