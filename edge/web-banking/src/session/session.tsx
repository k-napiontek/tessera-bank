/**
 * Who the customer is, for as long as the tab is open. Nothing longer.
 *
 * Two things live here and both are unusual, for reasons the estate forces:
 *
 * **The token is supplied, not obtained.** ADR 0007 records that the gateway validates a bearer
 * token and mints none, because an edge component holding a signing key could mint any identity in
 * the bank - and no other component issues one either. There is therefore no sign-in exchange to
 * implement. Inventing a fake one would be the most dishonest thing this application could do.
 *
 * **The account references are supplied too.** No operation in the contract lists a customer's
 * accounts; every one of them takes a reference the caller already holds. So the session is told
 * which accounts it may show, and the dashboard reads exactly those.
 *
 * Both are follow-ups against the estate rather than defects here. Both are written on the screen
 * the customer sees, because a UI that hides which part of it is scaffolding teaches its reader
 * something false about the system behind it.
 *
 * The token is held in React state - a closure variable in memory - and never in `localStorage` or
 * `sessionStorage`. Storage is readable by every script the page loads and survives the tab; state
 * dies with the page, which for a bearer token is the correct lifetime.
 */

import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

const ACCOUNT_REF = /^TB[0-9A-Z]{14}$/;

export interface SignedIn {
  readonly status: 'signed-in';
  readonly token: string;
  readonly accountRefs: readonly string[];
  readonly signOut: () => void;
}

export interface SignedOut {
  readonly status: 'signed-out';
  readonly signIn: (token: string, accountRefs: readonly string[]) => void;
}

export type Session = SignedIn | SignedOut;

/**
 * Account references from whatever the customer pasted in.
 *
 * Validated against the contract's own pattern here rather than left to fail as a 404 three
 * screens later: these arrive by hand precisely because nothing enumerates them, so a typo is the
 * expected input rather than the exceptional one.
 */
export function parseAccountRefs(input: string): readonly string[] {
  const candidates = input
    .split(/[\s,;]+/)
    .map((value) => value.trim())
    .filter((value) => value !== '');

  if (candidates.length === 0) {
    throw new Error('Enter at least one account reference.');
  }

  const malformed = candidates.filter((value) => !ACCOUNT_REF.test(value));
  if (malformed.length > 0) {
    throw new Error(
      `Not an account reference: ${malformed.join(', ')}. They look like TB00000000000001.`,
    );
  }

  return [...new Set(candidates)];
}

const SessionContext = createContext<Session | undefined>(undefined);

interface Held {
  readonly token: string;
  readonly accountRefs: readonly string[];
}

export function SessionProvider({ children }: { children: ReactNode }): React.JSX.Element {
  const [held, setHeld] = useState<Held | undefined>(undefined);

  const signIn = useCallback((token: string, accountRefs: readonly string[]) => {
    setHeld({ token, accountRefs });
  }, []);

  const signOut = useCallback(() => {
    setHeld(undefined);
  }, []);

  const session = useMemo<Session>(
    () =>
      held === undefined
        ? { status: 'signed-out', signIn }
        : { status: 'signed-in', token: held.token, accountRefs: held.accountRefs, signOut },
    [held, signIn, signOut],
  );

  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (session === undefined) {
    throw new Error('useSession was called outside a SessionProvider');
  }
  return session;
}

/** The signed-in session, for a screen that only ever renders inside one. */
export function useSignedIn(): SignedIn {
  const session = useSession();
  if (session.status !== 'signed-in') {
    throw new Error('this screen requires a signed-in session');
  }
  return session;
}
