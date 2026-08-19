import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { money } from '../money';
import { GatewayError, ProblemError, TransportError, createClient } from './client';
import { PROBLEM_NAMESPACE } from './problem';

const GATEWAY = 'https://gateway.test/v1';
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

const client = () => createClient({ baseUrl: GATEWAY, token: 'the-token' });

const account = {
  accountRef: 'TB00000000000001',
  customerRef: 'CU0000000001',
  accountType: 'LIABILITY',
  currency: 'PLN',
  status: 'OPEN',
  bookedBalance: { amountMinor: 250000, currency: 'PLN' },
  availableBalance: { amountMinor: 190000, currency: 'PLN' },
  openedDate: '2026-01-04',
  lastMovementDate: '2026-08-18',
};

describe('every request the client makes', () => {
  it('carries the bearer token and asks for the gateway, never the ledger', async () => {
    let seen: Request | undefined;
    server.use(
      http.get(`${GATEWAY}/accounts/TB00000000000001`, ({ request }) => {
        seen = request;
        return HttpResponse.json(account);
      }),
    );

    await client().getAccount('TB00000000000001');

    expect(seen?.headers.get('Authorization')).toBe('Bearer the-token');
    expect(seen?.headers.get('Accept')).toContain('application/json');
    expect(new URL(seen?.url ?? '').origin).toBe('https://gateway.test');
  });

  it('sends a correlation id the estate can trace the request by', async () => {
    let seen: Request | undefined;
    server.use(
      http.get(`${GATEWAY}/accounts/TB00000000000001`, ({ request }) => {
        seen = request;
        return HttpResponse.json(account);
      }),
    );

    await client().getAccount('TB00000000000001');

    expect(seen?.headers.get('X-Correlation-Id')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
  });
});

describe('reading an account', () => {
  it('decodes both balances as exact minor units', async () => {
    server.use(http.get(`${GATEWAY}/accounts/TB00000000000001`, () => HttpResponse.json(account)));

    const found = await client().getAccount('TB00000000000001');

    expect(found.bookedBalance).toEqual(money(250000n, 'PLN'));
    expect(found.availableBalance).toEqual(money(190000n, 'PLN'));
    expect(found.status).toBe('OPEN');
    expect(found.lastMovementDate).toBe('2026-08-18');
  });

  it('reads an amount beyond what a double can hold', async () => {
    // The contract types amountMinor as int64. JSON.parse would round this one silently.
    server.use(
      http.get(`${GATEWAY}/accounts/TB00000000000001`, () =>
        HttpResponse.text(
          JSON.stringify(account).replace('"amountMinor":250000', '"amountMinor":9223372036854775807'),
          { headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );

    const found = await client().getAccount('TB00000000000001');

    expect(found.bookedBalance.minorUnits).toBe(9223372036854775807n);
  });
});

describe('reading a statement', () => {
  const page = {
    accountRef: 'TB00000000000001',
    from: '2026-08-01',
    to: '2026-08-19',
    openingBalance: { amountMinor: 100000, currency: 'PLN' },
    closingBalance: { amountMinor: 95000, currency: 'PLN' },
    movements: [
      {
        movementRef: 'TB202608170000000042-01',
        transferRef: 'TB202608170000000042',
        legNo: 1,
        accountRef: 'TB00000000000001',
        direction: 'DEBIT',
        amount: { amountMinor: 5000, currency: 'PLN' },
        valueDate: '2026-08-17',
        postedAt: '2026-08-17T09:15:00Z',
      },
    ],
    nextCursor: 'opaque-cursor-value',
  };

  it('passes the range and hands the cursor back exactly as it arrived', async () => {
    let seen: URL | undefined;
    server.use(
      http.get(`${GATEWAY}/accounts/TB00000000000001/statement`, ({ request }) => {
        seen = new URL(request.url);
        return HttpResponse.json(page);
      }),
    );

    await client().getStatement('TB00000000000001', {
      from: '2026-08-01',
      to: '2026-08-19',
      cursor: 'opaque-cursor-value',
    });

    expect(seen?.searchParams.get('from')).toBe('2026-08-01');
    expect(seen?.searchParams.get('to')).toBe('2026-08-19');
    expect(seen?.searchParams.get('cursor')).toBe('opaque-cursor-value');
  });

  it('omits the cursor entirely when asking for the first page', async () => {
    let seen: URL | undefined;
    server.use(
      http.get(`${GATEWAY}/accounts/TB00000000000001/statement`, ({ request }) => {
        seen = new URL(request.url);
        return HttpResponse.json({ ...page, nextCursor: null });
      }),
    );

    const first = await client().getStatement('TB00000000000001', { from: '2026-08-01', to: '2026-08-19' });

    expect(seen?.searchParams.has('cursor')).toBe(false);
    expect(first.nextCursor).toBeNull();
  });
});

const postedTransfer = (amountMinor: number | string = 5000) => ({
  transferRef: 'TB202608190000000001',
  debitAccountRef: 'TB00000000000001',
  creditAccountRef: 'TB00000000000002',
  amount: { amountMinor, currency: 'PLN' },
  status: 'POSTED',
  reference: 'rent',
  requestedAt: '2026-08-19T10:00:00Z',
  postedAt: '2026-08-19T10:00:01Z',
  reversesTransferRef: null,
  movements: [
    {
      movementRef: 'TB202608190000000001-01',
      transferRef: 'TB202608190000000001',
      legNo: 1,
      accountRef: 'TB00000000000001',
      direction: 'DEBIT',
      amount: { amountMinor, currency: 'PLN' },
      valueDate: '2026-08-19',
      postedAt: '2026-08-19T10:00:01Z',
    },
    {
      movementRef: 'TB202608190000000001-02',
      transferRef: 'TB202608190000000001',
      legNo: 2,
      accountRef: 'TB00000000000002',
      direction: 'CREDIT',
      amount: { amountMinor, currency: 'PLN' },
      valueDate: '2026-08-19',
      postedAt: '2026-08-19T10:00:01Z',
    },
  ],
});

describe('creating a transfer', () => {
  const request = {
    debitAccountRef: 'TB00000000000001',
    creditAccountRef: 'TB00000000000002',
    amount: money(5000n, 'PLN'),
    reference: 'rent',
  };

  it('sends the idempotency key it was given, unchanged', async () => {
    let seen: Request | undefined;
    let body: unknown;
    server.use(
      http.post(`${GATEWAY}/transfers`, async ({ request: received }) => {
        seen = received;
        body = await received.json();
        return HttpResponse.json(postedTransfer(), { status: 201 });
      }),
    );

    await client().createTransfer(request, 'a-key-of-sufficient-length');

    expect(seen?.headers.get('Idempotency-Key')).toBe('a-key-of-sufficient-length');
    expect(body).toEqual({
      debitAccountRef: 'TB00000000000001',
      creditAccountRef: 'TB00000000000002',
      amount: { amountMinor: 5000, currency: 'PLN' },
      reference: 'rent',
    });
  });

  it('serialises an amount as a JSON integer, never as a string or a decimal', async () => {
    // 2^53 + 1. Written as digits in a string, never as a number literal - the linter refuses one
    // here for the same reason this test exists, which is a pleasing amount of agreement.
    const beyondDouble = '9007199254740993';
    let raw = '';
    server.use(
      http.post(`${GATEWAY}/transfers`, async ({ request: received }) => {
        raw = await received.text();
        return HttpResponse.text(
          JSON.stringify(postedTransfer('EXACT')).replaceAll('"EXACT"', beyondDouble),
          { status: 201, headers: { 'Content-Type': 'application/json' } },
        );
      }),
    );

    const posted = await client().createTransfer(
      { ...request, amount: money(BigInt(beyondDouble), 'PLN') },
      'a-key-of-sufficient-length',
    );

    expect(raw).toContain(`"amountMinor":${beyondDouble}`);
    expect(posted.amount.minorUnits).toBe(BigInt(beyondDouble));
  });
});

describe('when the gateway answers with a problem', () => {
  it('raises a ProblemError carrying the parsed document', async () => {
    server.use(
      http.post(`${GATEWAY}/transfers`, () =>
        HttpResponse.json(
          {
            type: `${PROBLEM_NAMESPACE}insufficient-funds`,
            title: 'Insufficient funds',
            status: 422,
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const failure = await client()
      .createTransfer(
        { debitAccountRef: 'TB00000000000001', creditAccountRef: 'TB00000000000002', amount: money(1n, 'PLN') },
        'a-key-of-sufficient-length',
      )
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ProblemError);
    expect(failure).toBeInstanceOf(GatewayError);
    expect((failure as ProblemError).problem.slug).toBe('insufficient-funds');
  });

  it('still raises a ProblemError when the body is not a problem document', async () => {
    server.use(
      http.get(`${GATEWAY}/accounts/TB00000000000001`, () =>
        HttpResponse.text('<html>502</html>', { status: 502 }),
      ),
    );

    const failure = await client()
      .getAccount('TB00000000000001')
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ProblemError);
    expect((failure as ProblemError).problem.status).toBe(502);
  });
});

describe('when no answer arrives at all', () => {
  it('raises a TransportError, which is a different thing from a rejection', async () => {
    // This is the distinction the whole pending state rests on: the bank did not say no, it said
    // nothing, and those two must never be rendered the same way.
    server.use(http.post(`${GATEWAY}/transfers`, () => HttpResponse.error()));

    const failure = await client()
      .createTransfer(
        { debitAccountRef: 'TB00000000000001', creditAccountRef: 'TB00000000000002', amount: money(1n, 'PLN') },
        'a-key-of-sufficient-length',
      )
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(TransportError);
    expect(failure).not.toBeInstanceOf(ProblemError);
  });
});
