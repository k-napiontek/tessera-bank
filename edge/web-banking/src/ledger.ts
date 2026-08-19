/**
 * The one rule of double entry this application has to know for itself.
 *
 * A `DEBIT` does not mean "money out". It means the left-hand side, and whether that increases or
 * reduces an account depends on the account's type: a customer's current account is a **liability**
 * of the bank, so it increases on the credit side. Read the direction as a sign and every statement
 * for a customer comes out backwards - which looks entirely plausible, because half the rows move
 * the right way.
 *
 * This mirrors `AccountType.signedEffect` in services/ledger-core. The duplication is deliberate
 * and small: the alternative is a UI that renders whichever sign the server happened to send, and
 * then a statement cannot be checked against its own opening and closing balances at all.
 */

import type { Money } from './money';
import { money, negate } from './money';
import type { AccountType, Direction, Movement } from './api/types';

const NORMAL_BALANCE: Readonly<Record<AccountType, Direction>> = Object.freeze({
  ASSET: 'DEBIT',
  LIABILITY: 'CREDIT',
  EQUITY: 'CREDIT',
  REVENUE: 'CREDIT',
  EXPENSE: 'DEBIT',
});

/** Positive when the posting increases the account, negative when it reduces it. */
export function signedEffect(accountType: AccountType, direction: Direction, amount: Money): Money {
  return direction === NORMAL_BALANCE[accountType] ? amount : negate(amount);
}

/**
 * Whether a page of a statement foots: opening plus every movement on it equals closing.
 *
 * The contract calls this self-proving and means it - each page foots on its own, and one page's
 * closing balance is the next page's opening. Checking it here costs nothing and catches the class
 * of failure a customer cannot: a movement dropped between the query and the response.
 */
export function pageFoots(
  accountType: AccountType,
  openingBalance: Money,
  movements: readonly Movement[],
  closingBalance: Money,
): boolean {
  let running = openingBalance;
  for (const movement of movements) {
    const effect = signedEffect(accountType, movement.direction, movement.amount);
    if (effect.currency !== running.currency) {
      return false;
    }
    running = money(running.minorUnits + effect.minorUnits, running.currency);
  }
  return running.currency === closingBalance.currency && running.minorUnits === closingBalance.minorUnits;
}
