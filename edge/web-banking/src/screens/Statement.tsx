/**
 * One account's movements over a range of value dates, a page at a time.
 *
 * Three things here follow the contract rather than convenience:
 *
 * **The cursor is opaque.** It is handed back exactly as it arrived and never read. The contract
 * says it encodes the server's sort key and is free to change; a client that parses it has coupled
 * itself to that.
 *
 * **Every page is checked to foot.** Opening plus the movements equals closing, per page. That is
 * the property the contract calls self-proving, and it is the only way this screen can notice a
 * movement dropped between the query and the response - a customer certainly cannot.
 *
 * **A direction is a side, not a sign.** What a `DEBIT` does to the balance depends on the account
 * type, so the account is fetched alongside the statement rather than assumed to be a customer's.
 */

import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useGateway } from '../api/GatewayProvider';
import { ProblemError, TransportError } from '../api/client';
import { describeProblem } from '../api/problem';
import type { Account, Statement as StatementPage } from '../api/types';
import { pageFoots, signedEffect } from '../ledger';
import { useRequest } from '../useRequest';
import { Amount } from './Amount';

function isoDate(daysAgo: number): string {
  const now = new Date();
  now.setUTCDate(now.getUTCDate() - daysAgo);
  return now.toISOString().slice(0, 10);
}

function explain(error: unknown): string {
  if (error instanceof ProblemError) {
    return describeProblem(error.problem);
  }
  if (error instanceof TransportError) {
    return 'We could not reach the bank just now.';
  }
  return 'Something went wrong reading this statement.';
}

interface Range {
  readonly from: string;
  readonly to: string;
}

export function Statement(): React.JSX.Element {
  const { accountRef = '' } = useParams();
  const { client } = useGateway();
  const [range, setRange] = useState<Range>({ from: isoDate(30), to: isoDate(0) });

  const { request: accountRequest } = useRequest<Account>(`account:${accountRef}`, (signal) =>
    client.getAccount(accountRef, signal),
  );

  return (
    <section className="statement">
      <p className="card-links">
        <Link to="/">Back to your accounts</Link>
      </p>
      <h2>Statement</h2>
      <p className="muted account-ref">{accountRef}</p>

      <RangePicker range={range} onChange={setRange} />

      {accountRequest.status === 'loading' && <p aria-live="polite">Loading…</p>}
      {accountRequest.status === 'failed' && (
        <p className="error" role="alert">
          {explain(accountRequest.error)}
        </p>
      )}
      {accountRequest.status === 'loaded' && (
        <Movements
          key={`${range.from}:${range.to}`}
          account={accountRequest.value}
          range={range}
        />
      )}
    </section>
  );
}

function RangePicker({
  range,
  onChange,
}: {
  range: Range;
  onChange: (range: Range) => void;
}): React.JSX.Element {
  return (
    <div className="range">
      <label htmlFor="statement-from">From</label>
      <input
        id="statement-from"
        type="date"
        value={range.from}
        onChange={(event) => {
          onChange({ ...range, from: event.target.value });
        }}
      />
      <label htmlFor="statement-to">To</label>
      <input
        id="statement-to"
        type="date"
        value={range.to}
        onChange={(event) => {
          onChange({ ...range, to: event.target.value });
        }}
      />
    </div>
  );
}

function Movements({ account, range }: { account: Account; range: Range }): React.JSX.Element {
  const { client } = useGateway();
  // Every page fetched so far, in the order the cursor walked them. Kept as pages rather than as
  // one flattened list because footing is a property of a page, and flattening loses it.
  const [pages, setPages] = useState<readonly StatementPage[]>([]);
  const [cursor, setCursor] = useState<string | undefined>(undefined);

  const { request, reload } = useRequest<StatementPage>(
    `statement:${account.accountRef}:${range.from}:${range.to}:${cursor ?? 'first'}`,
    useCallback(
      async (signal: AbortSignal) => {
        const page = await client.getStatement(
          account.accountRef,
          { from: range.from, to: range.to, ...(cursor === undefined ? {} : { cursor }) },
          signal,
        );
        setPages((previous) => (cursor === undefined ? [page] : [...previous, page]));
        return page;
      },
      [client, account.accountRef, range.from, range.to, cursor],
    ),
  );

  const last = pages.at(-1);
  const first = pages.at(0);

  if (request.status === 'failed') {
    return (
      <>
        <p className="error" role="alert">
          {explain(request.error)}
        </p>
        <button type="button" onClick={reload}>
          Try again
        </button>
      </>
    );
  }

  if (first === undefined || last === undefined) {
    return <p aria-live="polite">Loading…</p>;
  }

  const brokenPages = pages.filter(
    (page) => !pageFoots(account.accountType, page.openingBalance, page.movements, page.closingBalance),
  );
  // One page's closing balance is the next page's opening. A break here means the pages are not
  // consecutive, which the cursor is supposed to guarantee.
  const chainBreaks = pages.filter((page, index) => {
    const previous = pages[index - 1];
    return (
      previous !== undefined &&
      (previous.closingBalance.minorUnits !== page.openingBalance.minorUnits ||
        previous.closingBalance.currency !== page.openingBalance.currency)
    );
  });

  const movements = pages.flatMap((page) => page.movements);

  return (
    <>
      {(brokenPages.length > 0 || chainBreaks.length > 0) && (
        <p className="error" role="alert">
          This statement does not add up: the balances shown do not match the movements listed. Do
          not rely on it, and quote this account reference to us.
        </p>
      )}

      <dl className="balances">
        <div className="balance">
          <dt>Opening</dt>
          <dd>
            <Amount value={first.openingBalance} />
          </dd>
        </div>
        <div className="balance">
          <dt>Closing</dt>
          <dd>
            <Amount value={last.closingBalance} />
          </dd>
        </div>
      </dl>

      {movements.length === 0 ? (
        <p className="muted">Nothing posted in this range.</p>
      ) : (
        <table className="movements">
          <caption className="visually-hidden">
            Movements from {range.from} to {range.to}, oldest first
          </caption>
          <thead>
            <tr>
              <th scope="col">Value date</th>
              <th scope="col">Reference</th>
              <th scope="col">Side</th>
              <th scope="col">Effect</th>
            </tr>
          </thead>
          <tbody>
            {movements.map((movement) => (
              <tr key={movement.movementRef}>
                <td>{movement.valueDate}</td>
                <td className="mono">{movement.transferRef}</td>
                <td>{movement.direction === 'DEBIT' ? 'Debit' : 'Credit'}</td>
                <td className="numeric">
                  <Amount
                    value={signedEffect(account.accountType, movement.direction, movement.amount)}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {request.status === 'loading' && <p aria-live="polite">Loading…</p>}

      {last.nextCursor !== null && request.status !== 'loading' && (
        <button
          type="button"
          className="secondary"
          onClick={() => {
            setCursor(last.nextCursor ?? undefined);
          }}
        >
          Show earlier movements
        </button>
      )}
    </>
  );
}
