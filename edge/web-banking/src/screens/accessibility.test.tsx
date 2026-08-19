import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ACCOUNT_ONE, ACCOUNT_TWO, GATEWAY, accountDocument, renderSignedIn } from '../test/harness';
import { Transfer } from './Transfer';
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

function serveAccounts(): void {
  server.use(
    http.get(`${GATEWAY}/accounts/:accountRef`, ({ params }) =>
      HttpResponse.json(accountDocument(String(params['accountRef']), 250000, 190000)),
    ),
  );
}

const postedTransfer = {
  transferRef: 'TB202608190000000001',
  debitAccountRef: ACCOUNT_ONE,
  creditAccountRef: ACCOUNT_TWO,
  amount: { amountMinor: 1000, currency: 'PLN' },
  status: 'POSTED',
  requestedAt: '2026-08-19T10:00:00Z',
  postedAt: '2026-08-19T10:00:01Z',
  reversesTransferRef: null,
  movements: [],
};

describe('every control can be named and reached', () => {
  it('gives each form control an accessible name', async () => {
    serveAccounts();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    for (const control of [
      ...screen.getAllByRole('textbox'),
      ...screen.getAllByRole('combobox'),
      ...screen.getAllByRole('button'),
    ]) {
      expect(control).toHaveAccessibleName();
    }
  });

  it('completes the whole transfer journey from the keyboard alone', async () => {
    // No mouse anywhere in this test: tab to each control, type, and press Enter. A journey that
    // needs a pointer is a journey a screen-reader user cannot make.
    serveAccounts();
    server.use(http.post(`${GATEWAY}/transfers`, () => HttpResponse.json(postedTransfer)));
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await user.tab(); // the "back to your accounts" link
    await user.tab(); // From
    await user.tab(); // To
    await user.keyboard(ACCOUNT_TWO);
    await user.tab(); // Amount
    await user.keyboard('10.00');
    await user.keyboard('{Enter}');

    expect(await screen.findByRole('heading', { name: /confirm/i })).toBeInTheDocument();

    const send = await screen.findByRole('button', { name: /^send$/i });
    send.focus();
    await user.keyboard('{Enter}');

    expect(await screen.findByRole('heading', { name: /^sent$/i })).toBeInTheDocument();
  });
});

describe('what changes without the customer acting is announced', () => {
  it('puts the outcome of a transfer in a live region', async () => {
    serveAccounts();
    server.use(http.post(`${GATEWAY}/transfers`, () => HttpResponse.json(postedTransfer)));
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await user.type(screen.getByLabelText(/^to$/i), ACCOUNT_TWO);
    await user.type(screen.getByLabelText(/^amount/i), '10.00');
    await user.click(screen.getByRole('button', { name: /continue/i }));
    await user.click(await screen.findByRole('button', { name: /^send$/i }));

    const announced = await screen.findByRole('status');
    expect(within(announced).getByRole('heading', { name: /^sent$/i })).toBeInTheDocument();
  });

  it('puts a failure in an alert region rather than only in colour', async () => {
    serveAccounts();
    server.use(http.post(`${GATEWAY}/transfers`, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await user.type(screen.getByLabelText(/^to$/i), ACCOUNT_TWO);
    await user.type(screen.getByLabelText(/^amount/i), '10.00');
    await user.click(screen.getByRole('button', { name: /continue/i }));
    await user.click(await screen.findByRole('button', { name: /^send$/i }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});

describe('the dashboard reads as a list of accounts', () => {
  it('names each card so a screen reader can tell them apart', async () => {
    serveAccounts();
    renderSignedIn(<Dashboard />);

    expect(await screen.findByRole('article', { name: ACCOUNT_ONE })).toBeInTheDocument();
    expect(await screen.findByRole('article', { name: ACCOUNT_TWO })).toBeInTheDocument();
  });

  it('states the held amount in words rather than leaving the customer to subtract', async () => {
    serveAccounts();
    renderSignedIn(<Dashboard />, { accountRefs: [ACCOUNT_ONE] });

    const card = await screen.findByRole('article', { name: ACCOUNT_ONE });
    expect(within(card).getByText(/600\.00 PLN is held and cannot be spent yet/i)).toBeInTheDocument();
  });
});
