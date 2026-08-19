/**
 * The state of one attempt to make a transfer.
 *
 * The states are a union because the two that must never be confused - "the bank said no" and "the
 * bank said nothing" - are exactly the two a boolean `failed` flag merges. A transfer whose
 * response was lost may well have posted; presenting that as a failure invites the customer to send
 * the money a second time, which is the bug idempotency exists to prevent and which a UI can
 * reintroduce all by itself.
 *
 * **The idempotency key belongs to the attempt, not to the HTTP call.** It is minted once, when the
 * customer confirms what they are sending, and every retry of that request - after a rejection,
 * after a timeout, after a lost connection - reuses it. Changing the request mints a new one,
 * because a different request under the same key is a conflict rather than a retry.
 */

import type { Problem } from '../api/problem';
import type { Transfer, TransferRequest } from '../api/types';

export interface Draft {
  readonly debitAccountRef: string;
  readonly creditAccountRef: string;
  readonly amount: string;
  readonly reference: string;
}

export const EMPTY_DRAFT: Draft = {
  debitAccountRef: '',
  creditAccountRef: '',
  amount: '',
  reference: '',
};

export type Attempt =
  | { readonly stage: 'editing'; readonly draft: Draft; readonly error?: string }
  | {
      readonly stage: 'confirming';
      readonly draft: Draft;
      readonly request: TransferRequest;
      readonly idempotencyKey: string;
    }
  | {
      readonly stage: 'submitting';
      readonly draft: Draft;
      readonly request: TransferRequest;
      readonly idempotencyKey: string;
    }
  | {
      readonly stage: 'posted';
      readonly transfer: Transfer;
    }
  | {
      readonly stage: 'rejected';
      readonly draft: Draft;
      readonly request: TransferRequest;
      readonly idempotencyKey: string;
      readonly problem: Problem;
    }
  | {
      readonly stage: 'pending';
      readonly draft: Draft;
      readonly request: TransferRequest;
      readonly idempotencyKey: string;
      readonly reason: string;
    };

/**
 * The key for a request, given what the previous attempt used.
 *
 * Reused when the request is the one already confirmed, minted afresh when any field differs. The
 * comparison is over the request that goes on the wire, not over the form: a whitespace change the
 * encoder discards is the same request and must keep the same key.
 */
export function keyFor(
  request: TransferRequest,
  previous: { readonly request: TransferRequest; readonly idempotencyKey: string } | undefined,
): string {
  if (previous !== undefined && sameRequest(previous.request, request)) {
    return previous.idempotencyKey;
  }
  // The contract asks for 16 to 64 characters, opaque and unique per logical operation.
  return `tb-${crypto.randomUUID()}`;
}

export function sameRequest(left: TransferRequest, right: TransferRequest): boolean {
  return (
    left.debitAccountRef === right.debitAccountRef &&
    left.creditAccountRef === right.creditAccountRef &&
    left.amount.minorUnits === right.amount.minorUnits &&
    left.amount.currency === right.amount.currency &&
    (left.reference ?? '') === (right.reference ?? '') &&
    (left.valueDate ?? '') === (right.valueDate ?? '')
  );
}
