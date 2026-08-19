/**
 * The gap between booked and available, drawn.
 *
 * REQ-UI-003 says an available balance is never presented as spendable when held. The card already
 * discharges that in a sentence, and a sentence is the part that must never be removed. This adds
 * the other half: the share of the booked balance a hold has already committed, so a customer sees
 * the shape of it before they read the number.
 *
 * It is the only ornament on the screen, and it earns the place by being the one thing this screen
 * exists to get right. Where nothing is held there is nothing to show and it renders nothing at
 * all - a bar that is always full is decoration.
 */

import type { Money } from '../money';
import { compare, isSameCurrency, subtract, toPlainString } from '../money';

/**
 * The available share, as a CSS percentage.
 *
 * Bigint arithmetic down to the last step, then one division into a number. What comes out is the
 * width of a rectangle rather than a sum of money - `money.ts` holds the rule that an amount is
 * never a float, and this is not an amount. Nothing here is ever shown as a figure.
 */
function availableShare(booked: Money, available: Money): number {
  const basisPoints = (available.minorUnits * 10000n) / booked.minorUnits;
  const clamped = basisPoints < 0n ? 0n : basisPoints > 10000n ? 10000n : basisPoints;
  return Number(clamped) / 100;
}

export function BalanceMeter({
  booked,
  available,
}: {
  booked: Money;
  available: Money;
}): React.JSX.Element | null {
  if (!isSameCurrency(booked, available)) {
    throw new Error('a booked and an available balance in two currencies cannot be compared');
  }

  // An overdrawn account has no positive whole for a hold to be a share of, and a percentage of a
  // negative balance reads as the opposite of what it means.
  if (booked.minorUnits <= 0n || compare(booked, available) === 0) {
    return null;
  }

  const held = subtract(booked, available);
  const share = availableShare(booked, available);

  return (
    <div
      className="meter"
      role="img"
      aria-label={`${toPlainString(held)} ${held.currency} of ${toPlainString(booked)} ${booked.currency} is held`}
    >
      <span className="meter-available" style={{ width: `${String(share)}%` }} />
      <span className="meter-held" />
    </div>
  );
}
