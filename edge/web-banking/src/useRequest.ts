/**
 * One asynchronous read, with its three outcomes kept apart.
 *
 * `loading`, `loaded` and `failed` are a union rather than three booleans, because the states a
 * screen must not confuse are exactly the ones three independent flags let it confuse. The failure
 * keeps the error rather than a message, so the screen decides what a customer is told.
 *
 * Loading is **derived**, not assigned: the result is stored together with the token identifying
 * the attempt it belongs to, and anything that does not match the current token reads as loading.
 * Setting it in the effect instead would render once with the previous account's balance still on
 * screen under the new account's heading, which is a worse bug than it sounds.
 *
 * The caller names what identifies the request rather than handing over a dependency array. A
 * `key` says out loud which read this is - `account:TB00000000000001` - so two screens asking for
 * different things can never share a cached answer by accident, and the answer to "why did this
 * refetch" is readable rather than inferred from an array's contents.
 */

import { useCallback, useEffect, useState } from 'react';

export type Request<T> =
  | { readonly status: 'loading' }
  | { readonly status: 'loaded'; readonly value: T }
  | { readonly status: 'failed'; readonly error: unknown };

interface Entry<T> {
  readonly token: string;
  readonly request: Request<T>;
}

export function useRequest<T>(
  key: string,
  run: (signal: AbortSignal) => Promise<T>,
): { readonly request: Request<T>; readonly reload: () => void } {
  const [attempt, setAttempt] = useState(0);
  const [entry, setEntry] = useState<Entry<T> | undefined>(undefined);

  // What this attempt is. A result carries the token it was fetched under, so a late answer to a
  // question nobody is asking any more can be recognised and ignored rather than rendered.
  const token = `${key}#${String(attempt)}`;

  const reload = useCallback(() => {
    setAttempt((previous) => previous + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();

    run(controller.signal).then(
      (value) => {
        if (!controller.signal.aborted) {
          setEntry({ token, request: { status: 'loaded', value } });
        }
      },
      (error: unknown) => {
        // An abort is this effect being replaced, not a failure a customer should read about.
        if (!controller.signal.aborted) {
          setEntry({ token, request: { status: 'failed', error } });
        }
      },
    );

    return () => {
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const request: Request<T> =
    entry !== undefined && entry.token === token ? entry.request : { status: 'loading' };

  return { request, reload };
}
