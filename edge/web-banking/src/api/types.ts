/**
 * The shapes `contracts/openapi/ledger-core.yaml` declares, as this application holds them.
 *
 * Written by hand rather than generated. A generator would emit `amountMinor: number` from
 * `format: int64`, which is the one thing this tier must not do - see money.ts. Eleven operations
 * is also comfortably less code than a generator plus its configuration, and it stays readable.
 */

import type { Money } from '../money';

export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE';
export type AccountStatus = 'OPEN' | 'BLOCKED' | 'CLOSED';
export type Direction = 'DEBIT' | 'CREDIT';
export type TransferStatus = 'ACCEPTED' | 'POSTED' | 'REJECTED' | 'REVERSED';
export type HoldStatus = 'PLACED' | 'CAPTURED' | 'RELEASED' | 'EXPIRED';

export interface Account {
  readonly accountRef: string;
  readonly customerRef: string;
  readonly accountType: AccountType;
  readonly currency: string;
  readonly status: AccountStatus;
  readonly bookedBalance: Money;
  readonly availableBalance: Money;
  readonly openedDate: string;
  readonly lastMovementDate: string | null;
}

export interface Balance {
  readonly accountRef: string;
  readonly booked: Money;
  readonly available: Money;
  readonly asOf: string;
}

export interface Movement {
  readonly movementRef: string;
  readonly transferRef: string;
  readonly legNo: number;
  readonly accountRef: string;
  readonly direction: Direction;
  readonly amount: Money;
  readonly valueDate: string;
  readonly postedAt: string;
  readonly reference: string | undefined;
}

export interface Statement {
  readonly accountRef: string;
  readonly from: string;
  readonly to: string;
  readonly openingBalance: Money;
  readonly closingBalance: Money;
  readonly movements: readonly Movement[];
  /** Null means this page was the last. Opaque - never parsed, only handed back. */
  readonly nextCursor: string | null;
}

export interface Transfer {
  readonly transferRef: string;
  readonly debitAccountRef: string;
  readonly creditAccountRef: string;
  readonly amount: Money;
  readonly status: TransferStatus;
  readonly reference: string | undefined;
  readonly requestedAt: string;
  readonly postedAt: string | null;
  readonly reversesTransferRef: string | null;
  readonly movements: readonly Movement[];
}

export interface Hold {
  readonly holdRef: string;
  readonly accountRef: string;
  readonly amount: Money;
  readonly status: HoldStatus;
  readonly placedAt: string;
  readonly expiresAt: string | null;
}

export interface TransferRequest {
  readonly debitAccountRef: string;
  readonly creditAccountRef: string;
  readonly amount: Money;
  readonly reference?: string;
  readonly valueDate?: string;
}

export interface StatementQuery {
  readonly from: string;
  readonly to: string;
  readonly cursor?: string;
  readonly limit?: number;
}
