import { describe, expect, it } from 'vitest';
import { money } from './money';
import type { Movement } from './api/types';
import { pageFoots, signedEffect } from './ledger';

const movement = (direction: 'DEBIT' | 'CREDIT', minor: bigint): Movement => ({
  movementRef: 'TB202608170000000042-01',
  transferRef: 'TB202608170000000042',
  legNo: 1,
  accountRef: 'TB00000000000001',
  direction,
  amount: money(minor, 'PLN'),
  valueDate: '2026-08-17',
  postedAt: '2026-08-17T09:15:00Z',
  reference: undefined,
});

describe('the signed effect of a posting', () => {
  it('reduces a customer account on the debit side, because it is a liability of the bank', () => {
    expect(signedEffect('LIABILITY', 'DEBIT', money(100n, 'PLN')).minorUnits).toBe(-100n);
    expect(signedEffect('LIABILITY', 'CREDIT', money(100n, 'PLN')).minorUnits).toBe(100n);
  });

  it('does the opposite on an asset, which is the whole reason direction is not a sign', () => {
    expect(signedEffect('ASSET', 'DEBIT', money(100n, 'PLN')).minorUnits).toBe(100n);
    expect(signedEffect('ASSET', 'CREDIT', money(100n, 'PLN')).minorUnits).toBe(-100n);
  });

  it('treats equity, revenue and expense the way double entry does', () => {
    expect(signedEffect('EQUITY', 'CREDIT', money(1n, 'PLN')).minorUnits).toBe(1n);
    expect(signedEffect('REVENUE', 'CREDIT', money(1n, 'PLN')).minorUnits).toBe(1n);
    expect(signedEffect('EXPENSE', 'DEBIT', money(1n, 'PLN')).minorUnits).toBe(1n);
  });
});

describe('whether a page of a statement foots', () => {
  it('holds when opening plus the movements equals closing', () => {
    expect(
      pageFoots('LIABILITY', money(100000n, 'PLN'), [movement('DEBIT', 5000n)], money(95000n, 'PLN')),
    ).toBe(true);
  });

  it('holds for an empty page', () => {
    expect(pageFoots('LIABILITY', money(100000n, 'PLN'), [], money(100000n, 'PLN'))).toBe(true);
  });

  it('fails when a movement is missing from the page', () => {
    expect(
      pageFoots('LIABILITY', money(100000n, 'PLN'), [], money(95000n, 'PLN')),
    ).toBe(false);
  });

  it('fails when the direction is read as a sign rather than as a side', () => {
    // The mistake this function exists to catch: 100000 - 5000 is 95000, and 100000 + 5000 is
    // 105000, and only one of them is this customer's balance.
    expect(
      pageFoots('LIABILITY', money(100000n, 'PLN'), [movement('DEBIT', 5000n)], money(105000n, 'PLN')),
    ).toBe(false);
  });

  it('fails on a currency that does not belong to the page', () => {
    expect(
      pageFoots('LIABILITY', money(100000n, 'PLN'), [
        { ...movement('DEBIT', 5000n), amount: money(5000n, 'EUR') },
      ], money(95000n, 'PLN')),
    ).toBe(false);
  });
});
