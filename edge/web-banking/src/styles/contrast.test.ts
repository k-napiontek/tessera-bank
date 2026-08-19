/**
 * The stylesheet's own colours, held to WCAG 2.2 AA by arithmetic rather than by eye.
 *
 * The same control as `money.source.test.ts`, aimed at a different failure. A stylesheet does not
 * crash; it ships a colour that looks fine to whoever chose it and that a reader with low vision,
 * or anyone outdoors, cannot make out. Nothing fails, nobody is told, and the interface is simply
 * unusable for some of the people it was built for.
 *
 * So the pairs are declared here, in one list, and the numbers are computed from `tokens.css`
 * itself. A token darkened past its threshold fails this file before it reaches a screen. Every
 * colour token must appear in a pair or in `NOT_A_PAIR` with a reason - a colour nobody thought
 * about is exactly the one that goes wrong.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// Not import.meta.url: under jsdom Vite serves it as an http URL, and readFileSync refuses one.
// Same reason and same shape as money.source.test.ts - vitest runs from the package root.
const TOKENS = readFileSync(join(process.cwd(), 'src', 'styles', 'tokens.css'), 'utf8');

/** Every `--name: #rrggbb` declaration in the token sheet. */
function declaredColours(css: string): ReadonlyMap<string, string> {
  const found = new Map<string, string>();
  const declaration = /--([a-z0-9-]+)\s*:\s*(#[0-9a-f]{6})\s*;/gi;
  let match = declaration.exec(css);
  while (match !== null) {
    const [, name, hex] = match;
    if (name !== undefined && hex !== undefined) {
      found.set(name, hex.toLowerCase());
    }
    match = declaration.exec(css);
  }
  return found;
}

const COLOURS = declaredColours(TOKENS);

function channel(hex: string, offset: number): number {
  const value = Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
  // The sRGB transfer function, exactly as WCAG states it.
  return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
}

function luminance(hex: string): number {
  return (
    0.2126 * channel(hex, 1) + 0.7152 * channel(hex, 3) + 0.0722 * channel(hex, 5)
  );
}

function contrast(one: string, other: string): number {
  const a = luminance(one);
  const b = luminance(other);
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}

function hexOf(token: string): string {
  const hex = COLOURS.get(token);
  if (hex === undefined) {
    throw new Error(`tokens.css declares no --${token}`);
  }
  return hex;
}

/** Text on a ground. WCAG 1.4.3, 4.5:1 - this interface sets no text large enough to claim 3:1. */
const TEXT: readonly (readonly [string, string])[] = [
  ['ink', 'surface'],
  ['ink', 'page'],
  ['ink', 'surface-sunken'],
  ['ink-muted', 'surface'],
  ['ink-muted', 'page'],
  ['ink-muted', 'surface-sunken'],
  ['navy-700', 'surface'],
  ['navy-700', 'page'],
  ['navy-700', 'navy-050'],
  ['navy-900', 'surface'],
  ['red-600', 'surface'],
  ['red-600', 'page'],
  ['red-600', 'red-050'],
  ['green-700', 'surface'],
  ['green-700', 'green-050'],
  ['amber-800', 'surface'],
  ['amber-800', 'amber-050'],
  ['ink-inverse', 'navy-700'],
  ['ink-inverse', 'navy-900'],
  ['ink-inverse', 'red-600'],
];

/**
 * Boundaries and indicators. WCAG 1.4.11, 3:1.
 *
 * A control's edge is the only thing that says where the control is, so it is held to the same
 * standard as a word. `line` is deliberately absent: it separates rows and encloses cards, which
 * are grouped and identified by their content rather than by a rule, and darkening every hairline
 * to 3:1 would produce a page of cages.
 */
const NON_TEXT: readonly (readonly [string, string])[] = [
  ['control-border', 'surface'],
  ['control-border', 'page'],
  ['focus-ring', 'surface'],
  ['focus-ring', 'page'],
  // The two segments of the balance meter, which are read against each other.
  ['amber-500', 'navy-700'],
];

/**
 * Colours that are not a foreground over a background, with the reason each one is exempt.
 *
 * An entry here is a claim that has to be true. `amber-500` is the sharpest: it is PKO's
 * call-to-action amber and it reaches 2.6:1 on white, so it may fill a shape beside another fill
 * and it may never carry a word. `amber-800` exists because of it.
 */
const NOT_A_PAIR: Readonly<Record<string, string>> = {
  'navy-500': 'hover state of navy-700, never the resting colour of anything',
  'amber-500': 'meter fill and left border only - 2.6:1 on white, so it carries no text',
  line: 'hairline between rows and around cards; content identifies them, not the rule',
  'line-strong': 'divider one step up from line, same reasoning',
};

describe('the token sheet', () => {
  it('declares the colours the stylesheet expects', () => {
    expect(COLOURS.size).toBeGreaterThan(0);
  });

  it('accounts for every colour it declares', () => {
    const paired = new Set([...TEXT, ...NON_TEXT].flat());
    const unaccounted = [...COLOURS.keys()].filter(
      (name) => !paired.has(name) && !(name in NOT_A_PAIR),
    );
    expect(unaccounted).toEqual([]);
  });

  it('claims no exemption for a colour it does not declare', () => {
    const stale = Object.keys(NOT_A_PAIR).filter((name) => !COLOURS.has(name));
    expect(stale).toEqual([]);
  });
});

describe('text clears 4.5:1 on the ground it sits on', () => {
  it.each(TEXT)('%s on %s', (foreground, background) => {
    const ratio = contrast(hexOf(foreground), hexOf(background));
    expect(ratio, `--${foreground} on --${background} is ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5);
  });
});

describe('a boundary or an indicator clears 3:1', () => {
  it.each(NON_TEXT)('%s against %s', (foreground, background) => {
    const ratio = contrast(hexOf(foreground), hexOf(background));
    expect(ratio, `--${foreground} against --${background} is ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(3);
  });
});

describe('the arithmetic itself', () => {
  // Without these the suite would pass just as happily against a formula that returned 21 for
  // everything. The three values are from the WCAG definition, not from this implementation.
  it('puts black on white at 21:1', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5);
  });

  it('puts a colour against itself at 1:1', () => {
    expect(contrast('#003574', '#003574')).toBeCloseTo(1, 5);
  });

  it('puts mid grey on white at 4.6:1', () => {
    expect(contrast('#767676', '#ffffff')).toBeCloseTo(4.54, 2);
  });
});
