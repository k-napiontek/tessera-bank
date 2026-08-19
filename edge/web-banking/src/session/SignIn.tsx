/**
 * The sign-in screen, which asks for a token because the estate issues none.
 *
 * It says so on the page. A screen that showed a username and password box would imply an
 * authentication service that does not exist anywhere in this repository, and the first thing a
 * reader learns about a system is what its front door looks like.
 */

import { useId, useState } from 'react';
import { parseAccountRefs } from './session';

export interface SignInProps {
  readonly onSignIn: (token: string, accountRefs: readonly string[]) => void;
}

export function SignIn({ onSignIn }: SignInProps): React.JSX.Element {
  const tokenId = useId();
  const accountsId = useId();
  const [token, setToken] = useState('');
  const [accounts, setAccounts] = useState('');
  const [error, setError] = useState<string | undefined>(undefined);

  function submit(event: React.SyntheticEvent<HTMLFormElement>): void {
    event.preventDefault();
    if (token.trim() === '') {
      setError('Enter the access token you were given.');
      return;
    }
    try {
      const refs = parseAccountRefs(accounts);
      setError(undefined);
      onSignIn(token.trim(), refs);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Those details are not valid.');
    }
  }

  return (
    <form className="sign-in" onSubmit={submit} noValidate>
      <h2>Sign in</h2>

      <p className="note">
        Tessera Bank asks for an access token rather than a password: no component of this estate
        issues one, because the gateway validates tokens and deliberately holds no signing key.
      </p>

      <label htmlFor={tokenId}>Access token</label>
      <input
        id={tokenId}
        name="token"
        type="password"
        autoComplete="off"
        spellCheck={false}
        value={token}
        onChange={(event) => {
          setToken(event.target.value);
        }}
      />

      <label htmlFor={accountsId}>Account references</label>
      <p className="note" id={`${accountsId}-help`}>
        Nothing in this estate lists the accounts a customer holds, so enter the ones you want to
        see - separated by spaces or commas. They look like TB00000000000001.
      </p>
      <textarea
        id={accountsId}
        name="accounts"
        rows={2}
        autoComplete="off"
        spellCheck={false}
        aria-describedby={`${accountsId}-help`}
        value={accounts}
        onChange={(event) => {
          setAccounts(event.target.value);
        }}
      />

      {error !== undefined && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <button type="submit">Sign in</button>
    </form>
  );
}
