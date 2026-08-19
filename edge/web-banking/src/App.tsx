/**
 * The application: a session, and the screens that need one.
 *
 * Signed out there is one screen and no client, because without a token there is nothing this
 * application can usefully ask the gateway. That is why the client is built inside the signed-in
 * branch rather than held at the root with an empty token - and why the shell is given no way to
 * sign out until there is a session to end.
 */

import { Route, Routes } from 'react-router';
import { GatewayProvider } from './api/GatewayProvider';
import { AppShell } from './shell/AppShell';
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
      <AppShell onSignOut={onSignOut}>
        <Routes>
          <Route path={ROUTES.dashboard} element={<Dashboard />} />
          <Route path={ROUTES.statement} element={<Statement />} />
          <Route path={ROUTES.transfer} element={<Transfer />} />
        </Routes>
      </AppShell>
    </GatewayProvider>
  );
}

function Shell(): React.JSX.Element {
  const session = useSession();

  if (session.status === 'signed-out') {
    return (
      <AppShell>
        <SignIn onSignIn={session.signIn} />
      </AppShell>
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
