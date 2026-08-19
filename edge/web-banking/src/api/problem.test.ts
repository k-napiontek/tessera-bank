import { describe, expect, it } from 'vitest';
import { PROBLEM_NAMESPACE, describeProblem, parseProblem } from './problem';

const document = {
  type: `${PROBLEM_NAMESPACE}insufficient-funds`,
  title: 'Insufficient funds',
  status: 422,
  detail: 'The debit would take the account below its overdraft limit.',
  correlationId: '0b7f1a5e-9c3d-4a2b-8f61-2d4c6e8a0b13',
};

describe('reading a problem document', () => {
  it('keeps the machine-readable type, the title and the correlation id', () => {
    const problem = parseProblem(document, 422);
    expect(problem.type).toBe(`${PROBLEM_NAMESPACE}insufficient-funds`);
    expect(problem.slug).toBe('insufficient-funds');
    expect(problem.status).toBe(422);
    expect(problem.correlationId).toBe('0b7f1a5e-9c3d-4a2b-8f61-2d4c6e8a0b13');
  });

  it('reads the field violations a validation failure carries', () => {
    const problem = parseProblem(
      {
        type: `${PROBLEM_NAMESPACE}validation-failed`,
        title: 'Validation failed',
        status: 400,
        violations: [{ field: 'amount.amountMinor', message: 'must be strictly positive' }],
      },
      400,
    );
    expect(problem.violations).toEqual([
      { field: 'amount.amountMinor', message: 'must be strictly positive' },
    ]);
  });

  it('survives a body that is not a problem document at all', () => {
    // A proxy or a load balancer can answer with HTML, and the screen still has to say something.
    const problem = parseProblem('<html>502 Bad Gateway</html>', 502);
    expect(problem.status).toBe(502);
    expect(problem.slug).toBe('');
    expect(describeProblem(problem)).toMatch(/went wrong/i);
  });
});

describe('turning a problem into something a customer can read', () => {
  it('has wording of its own for every type the ledger emits', () => {
    const ledgerTypes = [
      'insufficient-funds',
      'currency-mismatch',
      'not-found',
      'validation-failed',
      'idempotency-conflict',
      'conflicting-state',
      'account-already-open',
      'not-actionable',
      'internal',
    ];
    for (const slug of ledgerTypes) {
      const problem = parseProblem({ type: `${PROBLEM_NAMESPACE}${slug}`, title: slug, status: 422 }, 422);
      expect(describeProblem(problem), slug).not.toBe(slug);
    }
  });

  it('has wording of its own for every type the gateway emits', () => {
    // F-34: these are in no contract, and this is now the second consumer that has to know them.
    const gatewayTypes = [
      'unauthenticated',
      'forbidden',
      'rate-limited',
      'payload-too-large',
      'no-route',
      'upstream-timeout',
      'upstream-unusable',
      'upstream-oversized',
    ];
    for (const slug of gatewayTypes) {
      const problem = parseProblem({ type: `${PROBLEM_NAMESPACE}${slug}`, title: slug, status: 503 }, 503);
      expect(describeProblem(problem), slug).not.toBe(slug);
    }
  });

  it('falls back to the document title rather than to a status code', () => {
    const problem = parseProblem(
      { type: `${PROBLEM_NAMESPACE}some-future-thing`, title: 'Some future thing', status: 409 },
      409,
    );
    expect(describeProblem(problem)).toBe('Some future thing');
  });

  it('never shows the raw type URI to a customer', () => {
    const problem = parseProblem(document, 422);
    expect(describeProblem(problem)).not.toContain(PROBLEM_NAMESPACE);
  });
});
