import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ACCOUNT_ONE, ACCOUNT_TWO, GATEWAY, accountDocument, renderSignedIn } from '../test/harness';
import { PROBLEM_NAMESPACE } from '../api/problem';
import { Transfer } from './Transfer';

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

interface Submission {
  readonly key: string | null;
  readonly body: string;
}

function serveAccount(): void {
  server.use(
    http.get(`${GATEWAY}/accounts/:accountRef`, () =>
      HttpResponse.json(accountDocument(ACCOUNT_ONE, 250000, 250000)),
    ),
  );
}

const postedTransfer = {
  transferRef: 'TB202608190000000001',
  debitAccountRef: ACCOUNT_ONE,
  creditAccountRef: ACCOUNT_TWO,
  amount: { amountMinor: 123456, currency: 'PLN' },
  status: 'POSTED',
  requestedAt: '2026-08-19T10:00:00Z',
  postedAt: '2026-08-19T10:00:01Z',
  reversesTransferRef: null,
  movements: [],
};

/** Records every POST /transfers and answers each one from `answers`, in order. */
function recordTransfers(answers: (() => Response)[]): Submission[] {
  const seen: Submission[] = [];
  server.use(
    http.post(`${GATEWAY}/transfers`, async ({ request }) => {
      seen.push({ key: request.headers.get('Idempotency-Key'), body: await request.text() });
      const answer = answers[Math.min(seen.length - 1, answers.length - 1)];
      return answer ? answer() : HttpResponse.json(postedTransfer);
    }),
  );
  return seen;
}

async function fillIn(
  user: ReturnType<typeof userEvent.setup>,
  amount = '1234.56',
): Promise<void> {
  await user.type(screen.getByLabelText(/^to$/i), ACCOUNT_TWO);
  await user.type(screen.getByLabelText(/^amount/i), amount);
  await user.click(screen.getByRole('button', { name: /continue/i }));
}

describe('entering a transfer', () => {
  it('shows what will be sent before it is sent', async () => {
    serveAccount();
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE, ACCOUNT_TWO] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);

    expect(await screen.findByRole('heading', { name: /confirm/i })).toBeInTheDocument();
    expect(screen.getByText('1234.56')).toBeInTheDocument();
  });

  it('converts the typed amount to minor units without ever touching a float', async () => {
    serveAccount();
    const seen = recordTransfers([]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /sent/i });

    // parseFloat('1234.56') * 100 is 123455.99999999999 on this machine.
    expect(seen[0]?.body).toContain('"amountMinor":123456');
  });

  it('refuses more decimals than the currency has rather than rounding them away', async () => {
    serveAccount();
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user, '10.005');

    expect(await screen.findByRole('alert')).toHaveTextContent(/2 decimal places/);
  });

  it('refuses an amount of zero', async () => {
    serveAccount();
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user, '0.00');

    expect(await screen.findByRole('alert')).toHaveTextContent(/greater than zero/i);
  });

  it('refuses paying an account into itself', async () => {
    serveAccount();
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await user.type(screen.getByLabelText(/^to$/i), ACCOUNT_ONE);
    await user.type(screen.getByLabelText(/^amount/i), '10.00');
    await user.click(screen.getByRole('button', { name: /continue/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/must be different/i);
  });
});

describe('the idempotency key', () => {
  it('is sent with the transfer and is the length the contract asks for', async () => {
    serveAccount();
    const seen = recordTransfers([]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /sent/i });

    const key = seen[0]?.key ?? '';
    expect(key.length).toBeGreaterThanOrEqual(16);
    expect(key.length).toBeLessThanOrEqual(64);
  });

  it('is identical on a retry after a rejection, and so is the body', async () => {
    // REQ-UI-002. A fresh key here is a second payment, which is the entire failure this exists
    // to prevent.
    serveAccount();
    const seen = recordTransfers([
      () =>
        HttpResponse.json(
          { type: `${PROBLEM_NAMESPACE}insufficient-funds`, title: 'Insufficient funds', status: 422 },
          { status: 422 },
        ),
      () => HttpResponse.json(postedTransfer),
    ]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /not sent/i });
    await user.click(screen.getByRole('button', { name: /try again/i }));
    await screen.findByRole('heading', { name: /sent/i });

    expect(seen).toHaveLength(2);
    expect(seen[0]?.key).toBe(seen[1]?.key);
    expect(seen[0]?.body).toBe(seen[1]?.body);
  });

  it('is replaced when the customer changes the details', async () => {
    serveAccount();
    const seen = recordTransfers([
      () =>
        HttpResponse.json(
          { type: `${PROBLEM_NAMESPACE}insufficient-funds`, title: 'Insufficient funds', status: 422 },
          { status: 422 },
        ),
      () => HttpResponse.json(postedTransfer),
    ]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /not sent/i });
    await user.click(screen.getByRole('button', { name: /change the details/i }));
    await user.clear(screen.getByLabelText(/^amount/i));
    await user.type(screen.getByLabelText(/^amount/i), '1.00');
    await user.click(screen.getByRole('button', { name: /continue/i }));
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /sent/i });

    expect(seen).toHaveLength(2);
    expect(seen[0]?.key).not.toBe(seen[1]?.key);
  });
});

describe('when the estate rejects the transfer', () => {
  it('says why in words a customer can act on, and offers the correlation id', async () => {
    serveAccount();
    recordTransfers([
      () =>
        HttpResponse.json(
          {
            type: `${PROBLEM_NAMESPACE}insufficient-funds`,
            title: 'Insufficient funds',
            status: 422,
            correlationId: '0b7f1a5e-9c3d-4a2b-8f61-2d4c6e8a0b13',
          },
          { status: 422 },
        ),
    ]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/not enough available balance/i);
    expect(screen.getByText(/0b7f1a5e-9c3d-4a2b-8f61-2d4c6e8a0b13/)).toBeInTheDocument();
  });
});

describe('when the outcome of the transfer is not known', () => {
  it('presents a dropped connection as pending, never as a failure', async () => {
    // The ledger may well have committed and lost the answer on the way back. Telling the customer
    // it failed invites them to send the money a second time.
    serveAccount();
    recordTransfers([() => HttpResponse.error()]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));

    expect(await screen.findByRole('heading', { name: /not yet known/i })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /not sent/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /^sent$/i })).not.toBeInTheDocument();
  });

  it('presents the gateway saying the ledger did not answer as pending too', async () => {
    serveAccount();
    recordTransfers([
      () =>
        HttpResponse.json(
          {
            type: `${PROBLEM_NAMESPACE}upstream-timeout`,
            title: 'The ledger did not answer in time',
            status: 504,
          },
          { status: 504 },
        ),
    ]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));

    expect(await screen.findByRole('heading', { name: /not yet known/i })).toBeInTheDocument();
  });

  it('tells the customer that checking cannot send the money twice', async () => {
    serveAccount();
    recordTransfers([() => HttpResponse.error()]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /not yet known/i });

    expect(screen.getByText(/rather than making a second one/i)).toBeInTheDocument();
  });

  it('resolves by re-sending the identical request under the identical key', async () => {
    // There is nothing to poll: the ledger allocates the transfer reference and a lost response
    // never carried it. Replaying the key is the only way to learn what happened.
    serveAccount();
    const seen = recordTransfers([() => HttpResponse.error(), () => HttpResponse.json(postedTransfer)]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /not yet known/i });
    await user.click(screen.getByRole('button', { name: /check again/i }));

    expect(await screen.findByRole('heading', { name: /^sent$/i })).toBeInTheDocument();
    expect(seen).toHaveLength(2);
    expect(seen[0]?.key).toBe(seen[1]?.key);
    expect(seen[0]?.body).toBe(seen[1]?.body);
  });

  it('stays pending when the check cannot reach the bank either', async () => {
    serveAccount();
    recordTransfers([() => HttpResponse.error()]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /not yet known/i });
    await user.click(screen.getByRole('button', { name: /check again/i }));

    expect(await screen.findByRole('heading', { name: /not yet known/i })).toBeInTheDocument();
  });

  it('shows the original transfer when the replay returns it', async () => {
    serveAccount();
    recordTransfers([
      () => HttpResponse.error(),
      // A replay answers 200 whatever the original returned - see the decision log.
      () => HttpResponse.json(postedTransfer, { status: 200 }),
    ]);
    const user = userEvent.setup();
    renderSignedIn(<Transfer />, { accountRefs: [ACCOUNT_ONE] });
    await screen.findByRole('button', { name: /continue/i });

    await fillIn(user);
    await user.click(await screen.findByRole('button', { name: /^send$/i }));
    await screen.findByRole('heading', { name: /not yet known/i });
    await user.click(screen.getByRole('button', { name: /check again/i }));

    expect(await screen.findByText('TB202608190000000001')).toBeInTheDocument();
  });
});
