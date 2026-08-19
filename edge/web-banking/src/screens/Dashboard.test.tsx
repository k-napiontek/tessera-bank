import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { screen, within } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ACCOUNT_ONE, ACCOUNT_TWO, GATEWAY, accountDocument, renderSignedIn } from '../test/harness';
import { PROBLEM_NAMESPACE } from '../api/problem';
import { Dashboard } from './Dashboard';

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

function serveAccounts(...documents: Record<string, unknown>[]): void {
  server.use(
    http.get(`${GATEWAY}/accounts/:accountRef`, ({ params }) => {
      const found = documents.find((document) => document['accountRef'] === params['accountRef']);
      return found
        ? HttpResponse.json(found)
        : HttpResponse.json(
            { type: `${PROBLEM_NAMESPACE}not-found`, title: 'Not found', status: 404 },
            { status: 404 },
          );
    }),
  );
}

describe('the dashboard', () => {
  it('shows one card per account reference the session was given', async () => {
    serveAccounts(
      accountDocument(ACCOUNT_ONE, 250000, 250000),
      accountDocument(ACCOUNT_TWO, 100000, 100000),
    );

    renderSignedIn(<Dashboard />);

    expect(await screen.findByRole('article', { name: ACCOUNT_ONE })).toBeInTheDocument();
    expect(await screen.findByRole('article', { name: ACCOUNT_TWO })).toBeInTheDocument();
  });

  it('labels booked and available as two figures, never one', async () => {
    // REQ-UI-003. One number where a hold exists tells the customer they can spend money they
    // cannot, and it is the single most common mis-statement a banking UI makes.
    serveAccounts(accountDocument(ACCOUNT_ONE, 250000, 190000));

    renderSignedIn(<Dashboard />, { accountRefs: [ACCOUNT_ONE] });

    const card = await screen.findByRole('article', { name: ACCOUNT_ONE });
    expect(within(card).getByText(/^booked$/i)).toBeInTheDocument();
    expect(within(card).getByText(/^available$/i)).toBeInTheDocument();
    expect(within(card).getByText('2500.00')).toBeInTheDocument();
    expect(within(card).getByText('1900.00')).toBeInTheDocument();
  });

  it('says why the two differ when they do', async () => {
    serveAccounts(accountDocument(ACCOUNT_ONE, 250000, 190000));

    renderSignedIn(<Dashboard />, { accountRefs: [ACCOUNT_ONE] });

    const card = await screen.findByRole('article', { name: ACCOUNT_ONE });
    expect(within(card).getByText(/600\.00 PLN is held/i)).toBeInTheDocument();
  });

  it('says nothing about holds when the two agree', async () => {
    serveAccounts(accountDocument(ACCOUNT_ONE, 250000, 250000));

    renderSignedIn(<Dashboard />, { accountRefs: [ACCOUNT_ONE] });

    const card = await screen.findByRole('article', { name: ACCOUNT_ONE });
    expect(within(card).queryByText(/is held/i)).not.toBeInTheDocument();
  });

  it('prints a negative available balance honestly rather than flooring it at zero', async () => {
    // Balance.available() reports a negative figure rather than clamping, and so does this.
    serveAccounts(accountDocument(ACCOUNT_ONE, 5000, -1500));

    renderSignedIn(<Dashboard />, { accountRefs: [ACCOUNT_ONE] });

    const card = await screen.findByRole('article', { name: ACCOUNT_ONE });
    expect(within(card).getByText('-15.00')).toBeInTheDocument();
  });

  it('shows an amount an int64 carries and a double cannot', async () => {
    server.use(
      http.get(`${GATEWAY}/accounts/:accountRef`, () =>
        HttpResponse.text(
          JSON.stringify(accountDocument(ACCOUNT_ONE, 0, 0)).replaceAll(
            '"amountMinor":0',
            '"amountMinor":9223372036854775807',
          ),
          { headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );

    renderSignedIn(<Dashboard />, { accountRefs: [ACCOUNT_ONE] });

    const card = await screen.findByRole('article', { name: ACCOUNT_ONE });
    expect(within(card).getAllByText('92233720368547758.07').length).toBeGreaterThan(0);
  });

  it('reports a failing account without blanking the ones that worked', async () => {
    serveAccounts(accountDocument(ACCOUNT_ONE, 250000, 250000));

    renderSignedIn(<Dashboard />);

    expect(await screen.findByRole('article', { name: ACCOUNT_ONE })).toBeInTheDocument();
    const failed = await screen.findByRole('article', { name: ACCOUNT_TWO });
    expect(within(failed).getByRole('alert')).toHaveTextContent(/could not find that account/i);
  });

  it('shows a status that is not OPEN, because it changes what the customer can do', async () => {
    serveAccounts(accountDocument(ACCOUNT_ONE, 250000, 250000, { status: 'BLOCKED' }));

    renderSignedIn(<Dashboard />, { accountRefs: [ACCOUNT_ONE] });

    const card = await screen.findByRole('article', { name: ACCOUNT_ONE });
    expect(within(card).getByText(/blocked/i)).toBeInTheDocument();
  });
});
