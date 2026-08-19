import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ACCOUNT_ONE, GATEWAY, accountDocument, renderSignedIn } from '../test/harness';
import { Statement } from './Statement';

const server = setupServer();
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

const movement = (
  legNo: number,
  direction: 'DEBIT' | 'CREDIT',
  amountMinor: number,
  valueDate: string,
): Record<string, unknown> => ({
  movementRef: `TB20260817000000004${String(legNo)}-01`,
  transferRef: `TB20260817000000004${String(legNo)}`,
  legNo: 1,
  accountRef: ACCOUNT_ONE,
  direction,
  amount: { amountMinor, currency: 'PLN' },
  valueDate,
  postedAt: `${valueDate}T09:15:00Z`,
});

const page = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  accountRef: ACCOUNT_ONE,
  from: '2026-07-20',
  to: '2026-08-19',
  openingBalance: { amountMinor: 100000, currency: 'PLN' },
  closingBalance: { amountMinor: 95000, currency: 'PLN' },
  movements: [movement(1, 'DEBIT', 5000, '2026-08-17')],
  nextCursor: null,
  ...overrides,
});

function renderStatement(): void {
  renderSignedIn(
    <Routes>
      <Route path="/accounts/:accountRef/statement" element={<Statement />} />
    </Routes>,
    { accountRefs: [ACCOUNT_ONE], route: `/accounts/${ACCOUNT_ONE}/statement` },
  );
}

function serveAccount(): void {
  server.use(
    http.get(`${GATEWAY}/accounts/:accountRef`, () =>
      HttpResponse.json(accountDocument(ACCOUNT_ONE, 95000, 95000)),
    ),
  );
}

describe('a statement', () => {
  it('lists movements oldest first with the value date and the transfer reference', async () => {
    serveAccount();
    server.use(http.get(`${GATEWAY}/accounts/:accountRef/statement`, () => HttpResponse.json(page())));

    renderStatement();

    const table = await screen.findByRole('table');
    expect(within(table).getByText('2026-08-17')).toBeInTheDocument();
    expect(within(table).getByText('TB202608170000000041')).toBeInTheDocument();
  });

  it('shows a debit on a customer account as reducing it', async () => {
    // A customer's current account is a liability of the bank, so a debit takes money out. Read
    // the direction as a sign instead and every customer statement comes out backwards.
    serveAccount();
    server.use(http.get(`${GATEWAY}/accounts/:accountRef/statement`, () => HttpResponse.json(page())));

    renderStatement();

    const table = await screen.findByRole('table');
    expect(within(table).getByText('-50.00')).toBeInTheDocument();
  });

  it('brackets the movements with the page opening and closing balances', async () => {
    serveAccount();
    server.use(http.get(`${GATEWAY}/accounts/:accountRef/statement`, () => HttpResponse.json(page())));

    renderStatement();

    expect(await screen.findByText('1000.00')).toBeInTheDocument();
    expect(await screen.findByText('950.00')).toBeInTheDocument();
  });

  it('refuses to present a page that does not foot', async () => {
    // A movement dropped between the query and the response leaves every figure plausible. This
    // is the only place the discrepancy can be noticed at all.
    serveAccount();
    server.use(
      http.get(`${GATEWAY}/accounts/:accountRef/statement`, () =>
        HttpResponse.json(page({ closingBalance: { amountMinor: 90000, currency: 'PLN' } })),
      ),
    );

    renderStatement();

    expect(await screen.findByRole('alert')).toHaveTextContent(/does not add up/i);
  });

  it('says nothing of the sort when the page foots', async () => {
    serveAccount();
    server.use(http.get(`${GATEWAY}/accounts/:accountRef/statement`, () => HttpResponse.json(page())));

    renderStatement();

    await screen.findByRole('table');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('asks for the first page without a cursor and hands the next one back untouched', async () => {
    serveAccount();
    const cursors: (string | null)[] = [];
    server.use(
      http.get(`${GATEWAY}/accounts/:accountRef/statement`, ({ request }) => {
        const asked = new URL(request.url).searchParams.get('cursor');
        cursors.push(asked);
        return asked === null
          ? HttpResponse.json(page({ nextCursor: 'c3Vydml2ZXMgdW50b3VjaGVk' }))
          : HttpResponse.json(
              page({
                openingBalance: { amountMinor: 95000, currency: 'PLN' },
                closingBalance: { amountMinor: 93000, currency: 'PLN' },
                movements: [movement(2, 'DEBIT', 2000, '2026-08-18')],
                nextCursor: null,
              }),
            );
      }),
    );

    renderStatement();

    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /show earlier movements/i }));

    expect(await screen.findByText('TB202608170000000042')).toBeInTheDocument();
    expect(cursors).toEqual([null, 'c3Vydml2ZXMgdW50b3VjaGVk']);
  });

  it('stops offering more once the cursor is null', async () => {
    serveAccount();
    server.use(http.get(`${GATEWAY}/accounts/:accountRef/statement`, () => HttpResponse.json(page())));

    renderStatement();

    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: /show earlier movements/i })).not.toBeInTheDocument();
  });

  it('reports when one page does not continue from the previous one', async () => {
    serveAccount();
    server.use(
      http.get(`${GATEWAY}/accounts/:accountRef/statement`, ({ request }) => {
        const asked = new URL(request.url).searchParams.get('cursor');
        return asked === null
          ? HttpResponse.json(page({ nextCursor: 'next' }))
          : HttpResponse.json(
              page({
                // Opens at a balance the previous page did not close at.
                openingBalance: { amountMinor: 80000, currency: 'PLN' },
                closingBalance: { amountMinor: 78000, currency: 'PLN' },
                movements: [movement(2, 'DEBIT', 2000, '2026-08-18')],
                nextCursor: null,
              }),
            );
      }),
    );

    renderStatement();

    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /show earlier movements/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/does not add up/i);
  });

  it('says so plainly when nothing posted in the range', async () => {
    serveAccount();
    server.use(
      http.get(`${GATEWAY}/accounts/:accountRef/statement`, () =>
        HttpResponse.json(
          page({ movements: [], closingBalance: { amountMinor: 100000, currency: 'PLN' } }),
        ),
      ),
    );

    renderStatement();

    expect(await screen.findByText(/nothing posted in this range/i)).toBeInTheDocument();
  });
});
