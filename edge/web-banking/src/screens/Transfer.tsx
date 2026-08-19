/**
 * Making a transfer: enter it, confirm it, and be told plainly what happened.
 *
 * The confirmation step is not ceremony. It is where the request stops changing, and therefore
 * where the idempotency key is minted - see transferAttempt.ts. Everything after it retries the
 * same request under the same key, so a retry can never become a second payment.
 */

import { useState } from 'react';
import { Link } from 'react-router';
import { useGateway } from '../api/GatewayProvider';
import { ProblemError } from '../api/client';
import { describeProblem, isOutcomeUnknown } from '../api/problem';
import type { Account, TransferRequest } from '../api/types';
import { minorUnitsFromDecimal, money, toPlainString } from '../money';
import { useRequest } from '../useRequest';
import { Amount } from './Amount';
import type { Attempt, Draft } from './transferAttempt';
import { EMPTY_DRAFT, keyFor } from './transferAttempt';

const ACCOUNT_REF = /^TB[0-9A-Z]{14}$/;

function buildRequest(draft: Draft, currency: string): TransferRequest {
  if (!ACCOUNT_REF.test(draft.creditAccountRef)) {
    throw new Error('The account you are paying does not look like an account reference.');
  }
  if (draft.creditAccountRef === draft.debitAccountRef) {
    throw new Error('The two accounts must be different.');
  }
  const minorUnits = minorUnitsFromDecimal(draft.amount.trim(), currency);
  if (minorUnits <= 0n) {
    throw new Error('Enter an amount greater than zero.');
  }
  const reference = draft.reference.trim();
  return {
    debitAccountRef: draft.debitAccountRef,
    creditAccountRef: draft.creditAccountRef,
    amount: money(minorUnits, currency),
    ...(reference === '' ? {} : { reference }),
  };
}

export function Transfer(): React.JSX.Element {
  const { client, accountRefs } = useGateway();
  const [attempt, setAttempt] = useState<Attempt>({
    stage: 'editing',
    draft: { ...EMPTY_DRAFT, debitAccountRef: accountRefs[0] ?? '' },
  });

  const debitRef = 'draft' in attempt ? attempt.draft.debitAccountRef : '';
  const { request: accountRequest } = useRequest<Account>(`account:${debitRef}`, (signal) =>
    client.getAccount(debitRef, signal),
  );
  const currency = accountRequest.status === 'loaded' ? accountRequest.value.currency : undefined;

  async function submit(
    draft: Draft,
    request: TransferRequest,
    idempotencyKey: string,
  ): Promise<void> {
    setAttempt({ stage: 'submitting', draft, request, idempotencyKey });
    try {
      const transfer = await client.createTransfer(request, idempotencyKey);
      setAttempt({ stage: 'posted', transfer });
    } catch (error) {
      if (error instanceof ProblemError && !isOutcomeUnknown(error.problem)) {
        setAttempt({ stage: 'rejected', draft, request, idempotencyKey, problem: error.problem });
        return;
      }
      // Either nothing answered, or what answered was a 5xx - which is not an answer about the
      // ledger. Both mean the outcome is unknown, and neither means the money did not move.
      setAttempt({
        stage: 'pending',
        draft,
        request,
        idempotencyKey,
        // A 5xx from infrastructure carries no problem type, and "we could not tell what" is a
        // poor thing to read under a heading about money. Say the uncertainty instead.
        reason:
          error instanceof ProblemError
            ? error.problem.slug === ''
              ? 'Something went wrong at our end, so we do not know whether this went through.'
              : describeProblem(error.problem)
            : 'We could not reach the bank, so we do not know whether this went through.',
      });
    }
  }

  return (
    <section className="transfer">
      <p className="card-links">
        <Link to="/">Back to your accounts</Link>
      </p>
      <h2>Make a transfer</h2>

      {attempt.stage === 'editing' && (
        <TransferForm
          draft={attempt.draft}
          accountRefs={accountRefs}
          currency={currency}
          error={attempt.error}
          onChange={(draft) => {
            setAttempt({ stage: 'editing', draft });
          }}
          onConfirm={(draft) => {
            if (currency === undefined) {
              return;
            }
            try {
              const request = buildRequest(draft, currency);
              setAttempt({
                stage: 'confirming',
                draft,
                request,
                idempotencyKey: keyFor(request, undefined),
              });
            } catch (failure) {
              setAttempt({
                stage: 'editing',
                draft,
                error: failure instanceof Error ? failure.message : 'Those details are not valid.',
              });
            }
          }}
        />
      )}

      {attempt.stage === 'confirming' && (
        <Confirmation
          request={attempt.request}
          onBack={() => {
            setAttempt({ stage: 'editing', draft: attempt.draft });
          }}
          onSend={() => {
            void submit(attempt.draft, attempt.request, attempt.idempotencyKey);
          }}
        />
      )}

      {attempt.stage === 'submitting' && (
        <p className="panel" aria-live="polite">
          Sending {toPlainString(attempt.request.amount)} {attempt.request.amount.currency}…
        </p>
      )}

      {attempt.stage === 'posted' && (
        <div className="panel" role="status">
          <h3>Sent</h3>
          <p>
            <Amount value={attempt.transfer.amount} /> went to{' '}
            <span className="mono">{attempt.transfer.creditAccountRef}</span>.
          </p>
          <p className="muted mono">{attempt.transfer.transferRef}</p>
          <p className="card-links">
            <Link to="/">Back to your accounts</Link>
          </p>
        </div>
      )}

      {attempt.stage === 'rejected' && (
        <div className="panel">
          <h3>Not sent</h3>
          <p className="error" role="alert">
            {describeProblem(attempt.problem)}
          </p>
          {attempt.problem.violations.map((violation) => (
            <p key={violation.field} className="error">
              {violation.field}: {violation.message}
            </p>
          ))}
          {attempt.problem.correlationId !== undefined && (
            <p className="muted mono">Reference for support: {attempt.problem.correlationId}</p>
          )}
          <button
            type="button"
            onClick={() => {
              // The same request under the same key. The ledger records no failure, so this is a
              // first attempt as far as it is concerned, and a retry as far as the customer is.
              void submit(attempt.draft, attempt.request, attempt.idempotencyKey);
            }}
          >
            Try again
          </button>{' '}
          <button
            type="button"
            className="secondary"
            onClick={() => {
              setAttempt({ stage: 'editing', draft: attempt.draft });
            }}
          >
            Change the details
          </button>
        </div>
      )}

      {attempt.stage === 'pending' && (
        <PendingTransfer
          reason={attempt.reason}
          onCheck={() => {
            void submit(attempt.draft, attempt.request, attempt.idempotencyKey);
          }}
        />
      )}
    </section>
  );
}

/**
 * The state a banking UI most often gets wrong.
 *
 * The ledger allocates the transfer reference and `TransferRequest` carries no client-supplied one,
 * so a lost response leaves this application holding nothing but its idempotency key. There is
 * therefore nothing to poll: the only way to find out what happened is to send the identical
 * request under the identical key and let the ledger's idempotency store replay whatever the first
 * attempt did. "Check again" does exactly that, and says so, because a customer pressing a button
 * labelled "send again" deserves to know it cannot send twice.
 */
function PendingTransfer({
  reason,
  onCheck,
}: {
  reason: string;
  onCheck: () => void;
}): React.JSX.Element {
  return (
    <div className="panel pending">
      <h3>Not yet known</h3>
      <p role="alert">{reason}</p>
      <p>
        Do not enter this transfer again. Checking sends the same request under the same reference,
        so if it already went through you will be shown that transfer rather than making a second
        one.
      </p>
      <button type="button" onClick={onCheck}>
        Check again
      </button>
    </div>
  );
}

function Confirmation({
  request,
  onBack,
  onSend,
}: {
  request: TransferRequest;
  onBack: () => void;
  onSend: () => void;
}): React.JSX.Element {
  return (
    <div className="panel">
      <h3>Confirm</h3>
      <dl className="confirm">
        <div>
          <dt>From</dt>
          <dd className="mono">{request.debitAccountRef}</dd>
        </div>
        <div>
          <dt>To</dt>
          <dd className="mono">{request.creditAccountRef}</dd>
        </div>
        <div>
          <dt>Amount</dt>
          <dd>
            <Amount value={request.amount} />
          </dd>
        </div>
        {request.reference !== undefined && (
          <div>
            <dt>Reference</dt>
            <dd>{request.reference}</dd>
          </div>
        )}
      </dl>
      <button type="button" onClick={onSend}>
        Send
      </button>{' '}
      <button type="button" className="secondary" onClick={onBack}>
        Back
      </button>
    </div>
  );
}

function TransferForm({
  draft,
  accountRefs,
  currency,
  error,
  onChange,
  onConfirm,
}: {
  draft: Draft;
  accountRefs: readonly string[];
  currency: string | undefined;
  error: string | undefined;
  onChange: (draft: Draft) => void;
  onConfirm: (draft: Draft) => void;
}): React.JSX.Element {
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onConfirm(draft);
      }}
      noValidate
    >
      <label htmlFor="transfer-from">From</label>
      <select
        id="transfer-from"
        value={draft.debitAccountRef}
        onChange={(event) => {
          onChange({ ...draft, debitAccountRef: event.target.value });
        }}
      >
        {accountRefs.map((accountRef) => (
          <option key={accountRef} value={accountRef}>
            {accountRef}
          </option>
        ))}
      </select>

      <label htmlFor="transfer-to">To</label>
      <input
        id="transfer-to"
        value={draft.creditAccountRef}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => {
          onChange({ ...draft, creditAccountRef: event.target.value.trim().toUpperCase() });
        }}
      />

      <label htmlFor="transfer-amount">Amount{currency === undefined ? '' : ` (${currency})`}</label>
      <input
        id="transfer-amount"
        inputMode="decimal"
        autoComplete="off"
        value={draft.amount}
        onChange={(event) => {
          onChange({ ...draft, amount: event.target.value });
        }}
      />

      <label htmlFor="transfer-reference">Reference (optional)</label>
      <input
        id="transfer-reference"
        maxLength={35}
        autoComplete="off"
        value={draft.reference}
        onChange={(event) => {
          onChange({ ...draft, reference: event.target.value });
        }}
      />

      {error !== undefined && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <button type="submit" disabled={currency === undefined}>
        Continue
      </button>
    </form>
  );
}
