/**
 * The application shell: a session, and the screens that need one.
 *
 * Signed out there is one screen and no client, because without a token there is nothing this
 * application can usefully ask the gateway. That is why the client is built inside the signed-in
 * branch rather than held at the root with an empty token.
 */

import { Link, Route, Routes } from 'react-router';
import { GatewayProvider } from './api/GatewayProvider';
import { Dashboard } from './screens/Dashboard';
import { Statement } from './screens/Statement';
import { Transfer } from './screens/Transfer';
import { SignIn } from './session/SignIn';
import { SessionProvider, useSession } from './session/session';
import { ROUTES } from './routes';

function SignedIn({
  token,
  accountRefs,
  onSignOut,
}: {
  token: string;
  accountRefs: readonly string[];
  onSignOut: () => void;
}): React.JSX.Element {
  return (
    <GatewayProvider token={token} accountRefs={accountRefs}>
      <header className="masthead">
        <h1>Tessera Bank</h1>
        <nav>
          <Link to={ROUTES.dashboard}>Accounts</Link>
          <Link to={ROUTES.transfer}>Transfer</Link>
        </nav>
        <button type="button" className="link-button" onClick={onSignOut}>
          Sign out
        </button>
      </header>
      <main>
        <Routes>
          <Route path={ROUTES.dashboard} element={<Dashboard />} />
          <Route path={ROUTES.statement} element={<Statement />} />
          <Route path={ROUTES.transfer} element={<Transfer />} />
        </Routes>
      </main>
    </GatewayProvider>
  );
}

function Shell(): React.JSX.Element {
  const session = useSession();

  if (session.status === 'signed-out') {
    return (
      <>
        <header className="masthead">
          <h1>Tessera Bank</h1>
        </header>
        <main>
          <SignIn onSignIn={session.signIn} />
        </main>
      </>
    );
  }

  return (
    <SignedIn
      token={session.token}
      accountRefs={session.accountRefs}
      onSignOut={session.signOut}
    />
  );
}

export function App(): React.JSX.Element {
  return (
    <SessionProvider>
      <Shell />
    </SessionProvider>
  );
}
