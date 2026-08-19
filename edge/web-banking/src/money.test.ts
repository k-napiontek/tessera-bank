import { describe, expect, it } from 'vitest';
import {
  UnknownCurrencyError,
  add,
  compare,
  isSameCurrency,
  minorUnitsFromDecimal,
  minorUnitsFromJson,
  parseJsonWithMinorUnits,
  money,
  negate,
  scaleOf,
  subtract,
  toPlainString,
} from './money';

describe('the scale table', () => {
  it('knows the scale of the currencies the estate carries', () => {
    expect(scaleOf('PLN')).toBe(2);
    expect(scaleOf('JPY')).toBe(0);
    expect(scaleOf('BHD')).toBe(3);
  });

  it('refuses a currency it does not know rather than assuming two decimals', () => {
    // Assuming 2 would misstate a JPY figure a hundredfold and look entirely ordinary.
    expect(() => scaleOf('XYZ')).toThrow(UnknownCurrencyError);
  });
});

describe('an amount', () => {
  it('is a whole count of minor units and its currency, and is frozen', () => {
    const m = money(123456789n, 'PLN');
    expect(m.minorUnits).toBe(123456789n);
    expect(m.currency).toBe('PLN');
    expect(Object.isFrozen(m)).toBe(true);
  });

  it('rejects a currency outside the table at construction', () => {
    expect(() => money(1n, 'XYZ')).toThrow(UnknownCurrencyError);
  });
});

describe('placing the decimal point', () => {
  it('slices digits rather than dividing', () => {
    expect(toPlainString(money(123456789n, 'PLN'))).toBe('1234567.89');
    expect(toPlainString(money(100n, 'JPY'))).toBe('100');
    expect(toPlainString(money(1234n, 'BHD'))).toBe('1.234');
  });

  it('pads an amount below one unit instead of shifting its sign', () => {
    // -5 PLN minor is -0.05, not -0.5. Sign-then-pad gets this wrong on every small amount.
    expect(toPlainString(money(-5n, 'PLN'))).toBe('-0.05');
    expect(toPlainString(money(5n, 'PLN'))).toBe('0.05');
    expect(toPlainString(money(0n, 'PLN'))).toBe('0.00');
  });

  it('holds an amount larger than a double can represent', () => {
    // 92 233 720 368 547 758.07 - beyond Number.MAX_SAFE_INTEGER, exact as a bigint.
    const huge = money(9223372036854775807n, 'PLN');
    expect(toPlainString(huge)).toBe('92233720368547758.07');
  });
});

describe('arithmetic', () => {
  it('adds and subtracts within one currency', () => {
    expect(add(money(100n, 'PLN'), money(23n, 'PLN')).minorUnits).toBe(123n);
    expect(subtract(money(100n, 'PLN'), money(123n, 'PLN')).minorUnits).toBe(-23n);
    expect(negate(money(100n, 'PLN')).minorUnits).toBe(-100n);
  });

  it('refuses to combine two currencies, because this estate holds no rates', () => {
    expect(() => add(money(100n, 'PLN'), money(100n, 'EUR'))).toThrow(/holds no rates/);
    expect(isSameCurrency(money(1n, 'PLN'), money(1n, 'EUR'))).toBe(false);
  });

  it('orders amounts of one currency', () => {
    expect(compare(money(1n, 'PLN'), money(2n, 'PLN'))).toBe(-1);
    expect(compare(money(2n, 'PLN'), money(2n, 'PLN'))).toBe(0);
    expect(compare(money(3n, 'PLN'), money(2n, 'PLN'))).toBe(1);
  });
});

describe('reading an amount the customer typed', () => {
  it('converts a decimal string to minor units without a float', () => {
    // parseFloat('1234.56') * 100 is 123455.99999999999 on this machine. This is the bug.
    expect(minorUnitsFromDecimal('1234.56', 'PLN')).toBe(123456n);
    expect(minorUnitsFromDecimal('0.07', 'PLN')).toBe(7n);
    expect(minorUnitsFromDecimal('1234', 'PLN')).toBe(123400n);
    expect(minorUnitsFromDecimal('100', 'JPY')).toBe(100n);
    expect(minorUnitsFromDecimal('1.234', 'BHD')).toBe(1234n);
  });

  it('accepts fewer decimals than the scale and pads them', () => {
    expect(minorUnitsFromDecimal('1234.5', 'PLN')).toBe(123450n);
  });

  it('refuses more decimals than the currency has', () => {
    // Rounding here would move money the customer did not agree to move.
    expect(() => minorUnitsFromDecimal('1.234', 'PLN')).toThrow(/2 decimal places/);
    expect(() => minorUnitsFromDecimal('100.5', 'JPY')).toThrow(/no decimal places/);
  });

  it('refuses anything that is not a plain decimal', () => {
    for (const bad of ['', ' ', '1,234.56', '1e3', 'abc', '-1.00', '+1.00', '1.2.3', '.5', '1.']) {
      expect(() => minorUnitsFromDecimal(bad, 'PLN')).toThrow();
    }
  });
});

describe('reading an amount off the wire', () => {
  it('takes the source text of a JSON integer, not the parsed double', () => {
    const body = '{"amountMinor":9223372036854775807,"currency":"PLN"}';
    expect(minorUnitsFromJson(body, 'amountMinor')).toBe(9223372036854775807n);
  });

  it('reads an ordinary amount unchanged', () => {
    expect(minorUnitsFromJson('{"amountMinor":123456789}', 'amountMinor')).toBe(123456789n);
    expect(minorUnitsFromJson('{"amountMinor":-5}', 'amountMinor')).toBe(-5n);
  });

  it('refuses a fractional amount - minor units are whole by definition', () => {
    expect(() => minorUnitsFromJson('{"amountMinor":1.5}', 'amountMinor')).toThrow();
  });
});

describe('reading a whole body', () => {
  it('replaces every amount in it with an exact bigint, however deeply nested', () => {
    const body = JSON.stringify({
      openingBalance: { amountMinor: 100000, currency: 'PLN' },
      movements: [{ amount: { amountMinor: 5000, currency: 'PLN' } }],
    });

    const parsed = parseJsonWithMinorUnits(body, (key) => key === 'amountMinor') as {
      openingBalance: { amountMinor: unknown };
      movements: { amount: { amountMinor: unknown } }[];
    };

    expect(parsed.openingBalance.amountMinor).toBe(100000n);
    expect(parsed.movements[0]?.amount.amountMinor).toBe(5000n);
  });

  it('takes an int64 amount from its source text rather than from the rounded double', () => {
    const body = '{"amount":{"amountMinor":9223372036854775807,"currency":"PLN"}}';

    const parsed = parseJsonWithMinorUnits(body, (key) => key === 'amountMinor') as {
      amount: { amountMinor: unknown };
    };

    expect(parsed.amount.amountMinor).toBe(9223372036854775807n);
  });

  it('leaves every other number alone', () => {
    const parsed = parseJsonWithMinorUnits('{"legNo":1}', (key) => key === 'amountMinor') as {
      legNo: unknown;
    };

    expect(parsed.legNo).toBe(1);
  });
});
