import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The money rule, enforced against the source rather than asserted about it.
 *
 * `batch/reporting/money.py` has a test of exactly this shape, for exactly this reason: a comment
 * saying "no floats here" is worth nothing the day somebody divides by 100 to render a figure. In
 * JavaScript it matters more than in Python, because there is no `Decimal` to reach for and no
 * `int` that stays exact - `number` is a double, always, and the mistake is one character wide.
 */

// Not import.meta.url: under jsdom Vite serves it as an http URL, and fileURLToPath refuses one.
// Vitest runs from the package root, so the source tree is one join away.
const SRC = join(process.cwd(), 'src');

/**
 * Source with comments, string literals, template literals and regular expressions removed, so
 * that a `/` inside `/^\d+$/` is not mistaken for a division.
 *
 * Whether a `/` opens a regular expression or divides is decided by the previous significant
 * token - which is the same question this test is asking, so the scanner and the check are one
 * thing rather than two that can disagree.
 */
function stripNonCode(source: string): string {
  const REGEX_MAY_FOLLOW = new Set(['(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', '+', '-', '*', '%', '^', '~', '<', '>', 'return']);
  let out = '';
  let previous = '';
  let index = 0;

  const lastSignificant = (): string => {
    const trimmed = out.trimEnd();
    const word = /(\breturn|\btypeof|\bcase)$/.exec(trimmed);
    return word ? word[0] : (trimmed.slice(-1) || '(');
  };

  while (index < source.length) {
    const two = source.slice(index, index + 2);
    const char = source[index] ?? '';

    if (two === '//') {
      index = source.indexOf('\n', index);
      if (index === -1) break;
      continue;
    }
    if (two === '/*') {
      const end = source.indexOf('*/', index + 2);
      index = end === -1 ? source.length : end + 2;
      continue;
    }
    if (char === '"' || char === "'" || char === '`') {
      const quote = char;
      index += 1;
      while (index < source.length && source[index] !== quote) {
        index += source[index] === '\\' ? 2 : 1;
      }
      index += 1;
      out += '""';
      continue;
    }
    if (char === '/' && REGEX_MAY_FOLLOW.has(lastSignificant())) {
      index += 1;
      let inClass = false;
      while (index < source.length) {
        const c = source[index];
        if (c === '\\') { index += 2; continue; }
        if (c === '[') inClass = true;
        else if (c === ']') inClass = false;
        else if (c === '/' && !inClass) break;
        index += 1;
      }
      index += 1;
      while (index < source.length && /[a-z]/.test(source[index] ?? '')) index += 1;
      out += 'RE';
      continue;
    }
    out += char;
    previous = char;
    index += 1;
  }
  void previous;
  return out;
}

function sourceFiles(directory: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      found.push(...sourceFiles(path));
    } else if (/\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) {
      found.push(path);
    }
  }
  return found;
}

describe('money.ts never touches a float', () => {
  const code = stripNonCode(readFileSync(join(SRC, 'money.ts'), 'utf8'));

  it('performs no division', () => {
    // The one that always happens: dividing minor units by 100 to render a figure, then letting
    // the divided value flow back into a total that reconciles to nothing.
    expect(code).not.toMatch(/\//);
  });

  it('performs no multiplication', () => {
    expect(code).not.toMatch(/\*/);
  });

  it('contains no fractional literal', () => {
    expect(code).not.toMatch(/\d\.\d/);
  });

  it('names none of the float conversions', () => {
    for (const forbidden of ['parseFloat', 'toFixed', 'toPrecision', 'Math.round', 'Math.floor']) {
      expect(code).not.toContain(forbidden);
    }
  });

  it('is proved by the check catching a planted division', () => {
    // A control nobody has seen fail is a control nobody has tested.
    const planted = stripNonCode('const shown = amount.minorUnits / 100n;');
    expect(planted).toMatch(/\//);
  });

  it('does not mistake a regular expression for a division', () => {
    expect(stripNonCode('const RE = /^-?\\d+$/;')).not.toMatch(/\//);
  });
});

describe('no module in the application converts an amount through a float', () => {
  it('names parseFloat, toFixed or toPrecision nowhere', () => {
    // money.ts is where an amount is supposed to be handled, but the mistake is just as easy in a
    // component rendering one - so the rule is checked over every source file rather than the one.
    const offenders: string[] = [];
    for (const file of sourceFiles(SRC)) {
      const code = stripNonCode(readFileSync(file, 'utf8'));
      for (const forbidden of ['parseFloat', 'toFixed', 'toPrecision']) {
        if (code.includes(forbidden)) {
          offenders.push(`${file} uses ${forbidden}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
