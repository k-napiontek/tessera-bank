import { describe, expect, it } from 'vitest';
import { money } from '../money';
import type { TransferRequest } from '../api/types';
import { keyFor, sameRequest } from './transferAttempt';

const request: TransferRequest = {
  debitAccountRef: 'TB00000000000001',
  creditAccountRef: 'TB00000000000002',
  amount: money(123456n, 'PLN'),
  reference: 'rent',
};

describe('the idempotency key for an attempt', () => {
  it('is minted when there is no previous attempt', () => {
    const key = keyFor(request, undefined);
    expect(key.length).toBeGreaterThanOrEqual(16);
    expect(key.length).toBeLessThanOrEqual(64);
  });

  it('is reused for the same request, which is what makes a retry a retry', () => {
    const first = keyFor(request, undefined);
    expect(keyFor(request, { request, idempotencyKey: first })).toBe(first);
  });

  it('is replaced when any field of the request changes', () => {
    // A different request under the same key is a 409 from the ledger, not a retry.
    const first = keyFor(request, undefined);
    const previous = { request, idempotencyKey: first };

    expect(keyFor({ ...request, amount: money(123457n, 'PLN') }, previous)).not.toBe(first);
    expect(keyFor({ ...request, creditAccountRef: 'TB00000000000003' }, previous)).not.toBe(first);
    expect(keyFor({ ...request, reference: 'other' }, previous)).not.toBe(first);
  });

  it('is different every time it is minted', () => {
    expect(keyFor(request, undefined)).not.toBe(keyFor(request, undefined));
  });
});

describe('comparing two requests', () => {
  it('compares the amount by minor units and currency, not by identity', () => {
    expect(sameRequest(request, { ...request, amount: money(123456n, 'PLN') })).toBe(true);
    expect(sameRequest(request, { ...request, amount: money(123456n, 'EUR') })).toBe(false);
  });

  it('treats an absent reference and an empty one as the same request', () => {
    const withoutReference: TransferRequest = {
      debitAccountRef: request.debitAccountRef,
      creditAccountRef: request.creditAccountRef,
      amount: request.amount,
    };
    expect(sameRequest(withoutReference, { ...withoutReference, reference: '' })).toBe(true);
  });
});
