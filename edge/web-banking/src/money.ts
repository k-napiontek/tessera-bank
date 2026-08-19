/**
 * Money: a whole count of minor units, and the ISO 4217 code that says how small they are.
 *
 * The estate's oldest rule, restated in a third language because this is the tier where it is
 * easiest to break. In JavaScript every `number` is the float the rule forbids, so an amount here
 * is a `bigint` and never a `number`. That is not caution about improbably large balances: the
 * contract types `amountMinor` as `int64`, and `Number.MAX_SAFE_INTEGER` is roughly a ninth of what
 * an int64 holds, so an ordinary JSON body can carry an amount a double cannot represent. It
 * arrives silently rounded, and every figure derived from it is then plausible and wrong.
 *
 * Nothing here divides, and nothing formats through `toFixed` or `parseFloat`. The decimal point is
 * placed by slicing digits. `money.source.test.ts` parses this file to prove it, rather than
 * trusting the sentence you are reading.
 *
 * The scale table is transcribed from docs/architecture/canonical-data-model.md and mirrors
 * batch/reporting/money.py. A currency absent from it is rejected, never assumed to have two
 * decimal places - which is the whole reason JPY and BHD are carried here at all.
 */

export interface Money {
  readonly minorUnits: bigint;
  readonly currency: string;
}

export const SCALES: Readonly<Record<string, number>> = Object.freeze({
  PLN: 2,
  EUR: 2,
  USD: 2,
  GBP: 2,
  CHF: 2,
  // Zero-scale currencies. Present so that a hard-coded 2 fails a test rather than passing quietly.
  JPY: 0,
  KRW: 0,
  // Three-scale currencies, carried for the same reason from the other side.
  BHD: 3,
  KWD: 3,
  TND: 3,
});

/**
 * A currency the scale table does not define.
 *
 * Guessing two decimal places produces a figure wrong by a factor of ten or a hundred that looks
 * entirely ordinary, which is the worst kind of wrong a balance can be.
 */
export class UnknownCurrencyError extends Error {
  readonly currency: string;

  constructor(currency: string) {
    super(
      `'${currency}' is not in the ISO 4217 scale table; add it to the canonical data model first, then here`,
    );
    this.name = 'UnknownCurrencyError';
    this.currency = currency;
  }
}

/** The number of decimal places `currency` has. */
export function scaleOf(currency: string): number {
  const scale = SCALES[currency];
  if (scale === undefined) {
    throw new UnknownCurrencyError(currency);
  }
  return scale;
}

/** An amount in minor units with its currency. `money(123456789n, 'PLN')` is 1 234 567.89. */
export function money(minorUnits: bigint, currency: string): Money {
  scaleOf(currency);
  return Object.freeze({ minorUnits, currency });
}

export function isSameCurrency(left: Money, right: Money): boolean {
  return left.currency === right.currency;
}

function requireSameCurrency(left: Money, right: Money): void {
  if (!isSameCurrency(left, right)) {
    // No conversion exists anywhere in this estate, deliberately. A rate applied here would be an
    // unsourced, undated rate shown to a customer as their balance.
    throw new Error(
      `cannot combine ${left.currency} and ${right.currency}: this estate holds no rates`,
    );
  }
}

export function add(left: Money, right: Money): Money {
  requireSameCurrency(left, right);
  return money(left.minorUnits + right.minorUnits, left.currency);
}

export function subtract(left: Money, right: Money): Money {
  requireSameCurrency(left, right);
  return money(left.minorUnits - right.minorUnits, left.currency);
}

export function negate(amount: Money): Money {
  return money(-amount.minorUnits, amount.currency);
}

/** -1, 0 or 1. Comparable only within one currency. */
export function compare(left: Money, right: Money): -1 | 0 | 1 {
  requireSameCurrency(left, right);
  if (left.minorUnits < right.minorUnits) return -1;
  if (left.minorUnits > right.minorUnits) return 1;
  return 0;
}

export function isNegative(amount: Money): boolean {
  return amount.minorUnits < 0n;
}

/**
 * The amount with its decimal point placed, for a human to read.
 *
 * Built by slicing digits. `-5 PLN` is `-0.05` and not `-0.5`, which is what a naive
 * sign-then-pad implementation returns for every amount below one unit.
 */
export function toPlainString(amount: Money): string {
  const scale = scaleOf(amount.currency);
  const negative = amount.minorUnits < 0n;
  const digits = (negative ? -amount.minorUnits : amount.minorUnits).toString();
  const sign = negative ? '-' : '';
  if (scale === 0) {
    return `${sign}${digits}`;
  }
  const padded = digits.padStart(scale + 1, '0');
  return `${sign}${padded.slice(0, padded.length - scale)}.${padded.slice(padded.length - scale)}`;
}

const DECIMAL_INPUT = /^(\d+)(?:\.(\d+))?$/;

/**
 * Minor units from what a customer typed into a form.
 *
 * The obvious implementation - `Math.round(parseFloat(text) * 100)` - is wrong on ordinary input:
 * `parseFloat('1234.56') * 100` is `123455.99999999999`, and rounding hides that until it does not.
 * This one never leaves the string domain.
 *
 * More decimals than the currency carries is refused rather than rounded. Rounding here would move
 * an amount the customer did not agree to move, which is not a form's decision to make.
 */
export function minorUnitsFromDecimal(text: string, currency: string): bigint {
  const scale = scaleOf(currency);
  const match = DECIMAL_INPUT.exec(text);
  if (!match) {
    throw new Error(`'${text}' is not a plain decimal amount`);
  }
  const whole = match[1] ?? '';
  const fraction = match[2] ?? '';
  if (fraction.length > scale) {
    throw new Error(
      scale === 0
        ? `${currency} has no decimal places, so '${text}' cannot be an amount in it`
        : `${currency} has ${String(scale)} decimal places, so '${text}' cannot be an amount in it`,
    );
  }
  return BigInt(whole + fraction.padEnd(scale, '0'));
}

interface ReviverContext {
  readonly source?: string;
}

/**
 * Minor units read from a JSON body, taken from the **source text** of the number.
 *
 * `JSON.parse` produces a double, so an int64 beyond 2^53 is already rounded by the time any
 * checking code could see it. The reviver's `context.source` is the digits as they arrived, which
 * `BigInt` reads exactly. Where a runtime does not offer it, an unsafe integer is refused rather
 * than accepted rounded - detection is weaker than exactness, and both beat silence.
 */
function exactMinorUnits(key: string, value: unknown, context: ReviverContext | undefined): bigint {
  const source = context?.source;
  if (typeof source === 'string') {
    if (!/^-?\d+$/.test(source)) {
      throw new Error(`'${key}' must be a whole count of minor units, got ${source}`);
    }
    return BigInt(source);
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new Error(
      `'${key}' cannot be read exactly by this runtime; minor units must be whole and within 2^53 here`,
    );
  }
  return BigInt(value);
}

export function minorUnitsFromJson(body: string, key: string): bigint {
  let found: bigint | undefined;
  JSON.parse(body, (revivedKey: string, value: unknown, context?: ReviverContext): unknown => {
    if (revivedKey === key) {
      found = exactMinorUnits(key, value, context);
    }
    return value;
  });
  if (found === undefined) {
    throw new Error(`no '${key}' in the body`);
  }
  return found;
}

/**
 * Parse a whole response body, replacing every amount with an exact `bigint`.
 *
 * The reviver is the only place a JSON number can be intercepted before it has been narrowed to a
 * double, so it is the only place this conversion can happen at all. Every amount in the estate's
 * contract sits under the key `amountMinor`, which is what makes one predicate enough.
 */
export function parseJsonWithMinorUnits(
  body: string,
  isAmountKey: (key: string) => boolean,
): unknown {
  return JSON.parse(body, (key: string, value: unknown, context?: ReviverContext): unknown =>
    isAmountKey(key) ? exactMinorUnits(key, value, context) : value,
  );
}
