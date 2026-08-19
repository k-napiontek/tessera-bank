/**
 * The accounts this session was told about, one card each.
 *
 * Each card is fetched independently and fails independently. One account the customer cannot see
 * must not blank the two they can - and since the references are typed in by hand (nothing in the
 * estate lists them), a reference that resolves to nothing is an ordinary outcome here rather than
 * an exceptional one.
 */

import { Link } from 'react-router';
import { useGateway } from '../api/GatewayProvider';
import { ProblemError, TransportError } from '../api/client';
import type { Account } from '../api/types';
import { describeProblem } from '../api/problem';
import { compare, subtract, toPlainString } from '../money';
import { useRequest } from '../useRequest';
import { statementPath } from '../routes';
import { Amount } from './Amount';

function explain(error: unknown): string {
  if (error instanceof ProblemError) {
    return describeProblem(error.problem);
  }
  if (error instanceof TransportError) {
    return 'We could not reach the bank just now.';
  }
  return 'Something went wrong reading this account.';
}

function AccountCard({ accountRef }: { accountRef: string }): React.JSX.Element {
  const { client } = useGateway();
  const { request, reload } = useRequest<Account>(`account:${accountRef}`, (signal) =>
    client.getAccount(accountRef, signal),
  );

  return (
    <article className="card" aria-label={accountRef}>
      <h3 className="card-title">{accountRef}</h3>

      {request.status === 'loading' && (
        <p className="muted" aria-live="polite">
          Loading…
        </p>
      )}

      {request.status === 'failed' && (
        <>
          <p className="error" role="alert">
            {explain(request.error)}
          </p>
          <button type="button" onClick={reload}>
            Try again
          </button>
        </>
      )}

      {request.status === 'loaded' && <AccountBalances account={request.value} />}
    </article>
  );
}

function AccountBalances({ account }: { account: Account }): React.JSX.Element {
  const held = subtract(account.bookedBalance, account.availableBalance);
  const differ = compare(account.bookedBalance, account.availableBalance) !== 0;

  return (
    <>
      {account.status !== 'OPEN' && (
        <p className="badge" data-status={account.status}>
          This account is {account.status.toLowerCase()}.
        </p>
      )}

      {/*
        Two figures, always, and labelled. Showing only "available" would be the friendlier design
        and would leave a customer unable to explain their own statement; showing only "booked"
        would tell them they can spend money a hold has already committed.
      */}
      <dl className="balances">
        <div className="balance">
          <dt>Booked</dt>
          <dd>
            <Amount value={account.bookedBalance} />
          </dd>
        </div>
        <div className="balance balance-available">
          <dt>Available</dt>
          <dd>
            <Amount value={account.availableBalance} />
          </dd>
        </div>
      </dl>

      {differ && (
        <p className="note">
          {toPlainString(held)} {held.currency} is held and cannot be spent yet.
        </p>
      )}

      <p className="card-links">
        <Link to={statementPath(account.accountRef)}>Statement</Link>
      </p>
    </>
  );
}

export function Dashboard(): React.JSX.Element {
  const { accountRefs } = useGateway();

  return (
    <section className="dashboard">
      <h2>Your accounts</h2>
      <div className="cards">
        {accountRefs.map((accountRef) => (
          <AccountCard key={accountRef} accountRef={accountRef} />
        ))}
      </div>
    </section>
  );
}
