/**
 * The application's only way out to the network.
 *
 * Everything goes through `edge/api-gateway`; nothing here knows the ledger's address, which is
 * WP-14's Out of scope stated as code rather than as a promise. The gateway validates the bearer
 * token, authorises by scope, matches the route against the OpenAPI contract and forwards - so a
 * URL this client builds that the contract does not declare comes back as `no-route`.
 *
 * Two failure kinds, and the difference between them is the point:
 *
 *   - `ProblemError` - the estate answered. Something is known about the outcome.
 *   - `TransportError` - nothing answered. **Nothing is known about the outcome**, and in
 *     particular a transfer may well have posted.
 *
 * Collapsing the two into "the request failed" is how a UI tells a customer their money did not
 * move when it did.
 */

import { money, parseJsonWithMinorUnits } from '../money';
import type { Money } from '../money';
import type { Problem } from './problem';
import { parseProblem } from './problem';
import type {
  Account,
  AccountStatus,
  AccountType,
  Balance,
  Direction,
  Hold,
  HoldStatus,
  Movement,
  Statement,
  StatementQuery,
  Transfer,
  TransferRequest,
  TransferStatus,
} from './types';

export abstract class GatewayError extends Error {}

/** The estate answered, and said no. */
export class ProblemError extends GatewayError {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.title === '' ? `request failed with ${String(problem.status)}` : problem.title);
    this.name = 'ProblemError';
    this.problem = problem;
  }
}

/**
 * Nothing answered - a dropped connection, a DNS failure, an abort.
 *
 * The request may have reached the ledger and committed. Anything that renders this as a failure
 * is asserting something it does not know.
 */
export class TransportError extends GatewayError {
  constructor(message: string, options?: { cause: unknown }) {
    super(message, options);
    this.name = 'TransportError';
  }
}

export interface ClientOptions {
  readonly baseUrl: string;
  readonly token: string;
  /** Injected in tests that need to drive a timeout; defaults to the platform's. */
  readonly fetch?: typeof globalThis.fetch;
}

interface RequestOptions {
  readonly method: 'GET' | 'POST';
  readonly path: string;
  readonly query?: Record<string, string | undefined>;
  readonly body?: unknown;
  readonly idempotencyKey?: string;
  readonly signal?: AbortSignal;
}

function asRecord(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TransportError(`the gateway returned something that is not ${what}`);
  }
  return value as Record<string, unknown>;
}

function requireString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== 'string') {
    throw new TransportError(`'${key}' is missing from the gateway's answer`);
  }
  return value;
}

function optionalString(record: Record<string, unknown>, key: string): string | undefined {
  const value = record[key];
  return typeof value === 'string' ? value : undefined;
}

function nullableString(record: Record<string, unknown>, key: string): string | null {
  const value = record[key];
  return typeof value === 'string' ? value : null;
}

function decodeMoney(value: unknown, what: string): Money {
  const record = asRecord(value, what);
  const minorUnits = record['amountMinor'];
  if (typeof minorUnits !== 'bigint') {
    throw new TransportError(`'${what}' carries no exact amount`);
  }
  return money(minorUnits, requireString(record, 'currency'));
}

function decodeMovement(value: unknown): Movement {
  const record = asRecord(value, 'a movement');
  const legNo = record['legNo'];
  return {
    movementRef: requireString(record, 'movementRef'),
    transferRef: requireString(record, 'transferRef'),
    legNo: typeof legNo === 'number' ? legNo : 0,
    accountRef: requireString(record, 'accountRef'),
    direction: requireString(record, 'direction') as Direction,
    amount: decodeMoney(record['amount'], 'a movement amount'),
    valueDate: requireString(record, 'valueDate'),
    postedAt: requireString(record, 'postedAt'),
    reference: optionalString(record, 'reference'),
  };
}

function decodeMovements(value: unknown): readonly Movement[] {
  return Array.isArray(value) ? value.map(decodeMovement) : [];
}

function decodeAccount(value: unknown): Account {
  const record = asRecord(value, 'an account');
  return {
    accountRef: requireString(record, 'accountRef'),
    customerRef: requireString(record, 'customerRef'),
    accountType: requireString(record, 'accountType') as AccountType,
    currency: requireString(record, 'currency'),
    status: requireString(record, 'status') as AccountStatus,
    bookedBalance: decodeMoney(record['bookedBalance'], 'the booked balance'),
    availableBalance: decodeMoney(record['availableBalance'], 'the available balance'),
    openedDate: requireString(record, 'openedDate'),
    lastMovementDate: nullableString(record, 'lastMovementDate'),
  };
}

function decodeBalance(value: unknown): Balance {
  const record = asRecord(value, 'a balance');
  return {
    accountRef: requireString(record, 'accountRef'),
    booked: decodeMoney(record['booked'], 'the booked balance'),
    available: decodeMoney(record['available'], 'the available balance'),
    asOf: requireString(record, 'asOf'),
  };
}

function decodeStatement(value: unknown): Statement {
  const record = asRecord(value, 'a statement');
  return {
    accountRef: requireString(record, 'accountRef'),
    from: requireString(record, 'from'),
    to: requireString(record, 'to'),
    openingBalance: decodeMoney(record['openingBalance'], 'the opening balance'),
    closingBalance: decodeMoney(record['closingBalance'], 'the closing balance'),
    movements: decodeMovements(record['movements']),
    nextCursor: nullableString(record, 'nextCursor'),
  };
}

function decodeTransfer(value: unknown): Transfer {
  const record = asRecord(value, 'a transfer');
  return {
    transferRef: requireString(record, 'transferRef'),
    debitAccountRef: requireString(record, 'debitAccountRef'),
    creditAccountRef: requireString(record, 'creditAccountRef'),
    amount: decodeMoney(record['amount'], 'the transfer amount'),
    status: requireString(record, 'status') as TransferStatus,
    reference: optionalString(record, 'reference'),
    requestedAt: requireString(record, 'requestedAt'),
    postedAt: nullableString(record, 'postedAt'),
    reversesTransferRef: nullableString(record, 'reversesTransferRef'),
    movements: decodeMovements(record['movements']),
  };
}

function decodeHold(value: unknown): Hold {
  const record = asRecord(value, 'a hold');
  return {
    holdRef: requireString(record, 'holdRef'),
    accountRef: requireString(record, 'accountRef'),
    amount: decodeMoney(record['amount'], 'the hold amount'),
    status: requireString(record, 'status') as HoldStatus,
    placedAt: requireString(record, 'placedAt'),
    expiresAt: nullableString(record, 'expiresAt'),
  };
}

/** An amount goes onto the wire as the integer the contract declares. */
function encodeMoney(amount: Money): string {
  return `{"amountMinor":${amount.minorUnits.toString()},"currency":${JSON.stringify(amount.currency)}}`;
}

function encodeTransferRequest(request: TransferRequest): string {
  const fields = [
    `"debitAccountRef":${JSON.stringify(request.debitAccountRef)}`,
    `"creditAccountRef":${JSON.stringify(request.creditAccountRef)}`,
    `"amount":${encodeMoney(request.amount)}`,
  ];
  if (request.reference !== undefined) {
    fields.push(`"reference":${JSON.stringify(request.reference)}`);
  }
  if (request.valueDate !== undefined) {
    fields.push(`"valueDate":${JSON.stringify(request.valueDate)}`);
  }
  return `{${fields.join(',')}}`;
}

const AMOUNT_KEY = 'amountMinor';

export interface GatewayClient {
  getAccount(accountRef: string, signal?: AbortSignal): Promise<Account>;
  getBalance(accountRef: string, signal?: AbortSignal): Promise<Balance>;
  getStatement(accountRef: string, query: StatementQuery, signal?: AbortSignal): Promise<Statement>;
  listHolds(accountRef: string, signal?: AbortSignal): Promise<readonly Hold[]>;
  createTransfer(
    request: TransferRequest,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<Transfer>;
  getTransfer(transferRef: string, signal?: AbortSignal): Promise<Transfer>;
}

export function createClient(options: ClientOptions): GatewayClient {
  const doFetch = options.fetch ?? globalThis.fetch.bind(globalThis);
  const base = options.baseUrl.replace(/\/+$/, '');

  async function send(request: RequestOptions): Promise<unknown> {
    const url = new URL(base + request.path);
    for (const [key, value] of Object.entries(request.query ?? {})) {
      if (value !== undefined) {
        url.searchParams.set(key, value);
      }
    }

    const headers = new Headers({
      Accept: 'application/json, application/problem+json',
      Authorization: `Bearer ${options.token}`,
      // Generated per request. The gateway mints one when absent, but then the client cannot name
      // the id a customer would have to quote, and the id is the whole point of having one.
      'X-Correlation-Id': crypto.randomUUID(),
    });
    if (request.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (request.idempotencyKey !== undefined) {
      headers.set('Idempotency-Key', request.idempotencyKey);
    }

    let response: Response;
    try {
      response = await doFetch(url.toString(), {
        method: request.method,
        headers,
        ...(typeof request.body === 'string' ? { body: request.body } : {}),
        ...(request.signal ? { signal: request.signal } : {}),
        // A bearer token must not ride along on a cross-origin request the app did not intend.
        credentials: 'omit',
        mode: 'cors',
        referrerPolicy: 'no-referrer',
      });
    } catch (cause) {
      throw new TransportError('the bank could not be reached', { cause });
    }

    const text = await response.text().catch(() => '');

    if (!response.ok) {
      let body: unknown = text;
      try {
        body = JSON.parse(text);
      } catch {
        // Not JSON at all - infrastructure the estate does not own can answer with anything.
      }
      throw new ProblemError(parseProblem(body, response.status));
    }

    if (text === '') {
      return {};
    }
    try {
      return parseJsonWithMinorUnits(text, (key) => key === AMOUNT_KEY);
    } catch (cause) {
      throw new TransportError("the gateway's answer could not be read", { cause });
    }
  }

  function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
    return signal ? { signal } : {};
  }

  return {
    async getAccount(accountRef, signal) {
      return decodeAccount(
        await send({ method: 'GET', path: `/accounts/${encodeURIComponent(accountRef)}`, ...withSignal(signal) }),
      );
    },

    async getBalance(accountRef, signal) {
      return decodeBalance(
        await send({
          method: 'GET',
          path: `/accounts/${encodeURIComponent(accountRef)}/balance`,
          ...withSignal(signal),
        }),
      );
    },

    async getStatement(accountRef, query, signal) {
      return decodeStatement(
        await send({
          method: 'GET',
          path: `/accounts/${encodeURIComponent(accountRef)}/statement`,
          query: {
            from: query.from,
            to: query.to,
            // Handed back exactly as it arrived. A client that parses a cursor has coupled itself
            // to the server's sort key, which the contract says is free to change.
            cursor: query.cursor,
            limit: query.limit === undefined ? undefined : String(query.limit),
          },
          ...withSignal(signal),
        }),
      );
    },

    async listHolds(accountRef, signal) {
      const body = await send({
        method: 'GET',
        path: `/accounts/${encodeURIComponent(accountRef)}/holds`,
        ...withSignal(signal),
      });
      return Array.isArray(body) ? body.map(decodeHold) : [];
    },

    async createTransfer(request, idempotencyKey, signal) {
      return decodeTransfer(
        await send({
          method: 'POST',
          path: '/transfers',
          body: encodeTransferRequest(request),
          idempotencyKey,
          ...withSignal(signal),
        }),
      );
    },

    async getTransfer(transferRef, signal) {
      return decodeTransfer(
        await send({
          method: 'GET',
          path: `/transfers/${encodeURIComponent(transferRef)}`,
          ...withSignal(signal),
        }),
      );
    },
  };
}
