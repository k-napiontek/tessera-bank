/**
 * An amount on screen: the figure, and the currency beside it.
 *
 * Never the figure alone. `Money` keeps the two together for the same reason the canonical model
 * does - 100 means nothing until you know whether it is PLN or JPY - and a component that drops
 * the code on the way to the DOM undoes that at the last possible moment.
 */

import type { Money } from '../money';
import { isNegative, toPlainString } from '../money';

export function Amount({ value, className }: { value: Money; className?: string }): React.JSX.Element {
  const classes = ['amount', isNegative(value) ? 'amount-negative' : '', className ?? '']
    .filter((name) => name !== '')
    .join(' ');
  return (
    <span className={classes}>
      <span className="amount-figure">{toPlainString(value)}</span>{' '}
      <span className="amount-currency">{value.currency}</span>
    </span>
  );
}
