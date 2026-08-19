/**
 * The client, and the account references this session may show, handed to the screens below.
 *
 * The account references sit here rather than in the session module because they are an argument
 * to every read the application performs - the estate lists nobody's accounts, so "which accounts"
 * is data the screens need, not a property of who is signed in.
 */

import { createContext, useContext, useMemo } from 'react';
import type { ReactNode } from 'react';
import type { GatewayClient } from './client';
import { createClient } from './client';

export function gatewayBaseUrl(): string {
  // Same origin, and **no path prefix**. The gateway serves the contract's own paths at its root;
  // `/v1` is the *ledger's* prefix, which the gateway adds itself from TB_GATEWAY_LEDGER_URL.
  // Sending it `/v1/accounts/...` is a `no-route`, and the live walkthrough is what caught it -
  // every hermetic test in this suite mocks whatever base URL it is given.
  return import.meta.env.VITE_GATEWAY_URL ?? window.location.origin;
}

interface Gateway {
  readonly client: GatewayClient;
  readonly accountRefs: readonly string[];
}

const GatewayContext = createContext<Gateway | undefined>(undefined);

export function GatewayProvider({
  children,
  client,
  token,
  accountRefs,
}: {
  children: ReactNode;
  client?: GatewayClient;
  token?: string;
  accountRefs: readonly string[];
}): React.JSX.Element {
  const value = useMemo<Gateway>(
    () => ({
      client: client ?? createClient({ baseUrl: gatewayBaseUrl(), token: token ?? '' }),
      accountRefs,
    }),
    [client, token, accountRefs],
  );
  return <GatewayContext.Provider value={value}>{children}</GatewayContext.Provider>;
}

export function useGateway(): Gateway {
  const gateway = useContext(GatewayContext);
  if (gateway === undefined) {
    throw new Error('useGateway was called outside a GatewayProvider');
  }
  return gateway;
}
