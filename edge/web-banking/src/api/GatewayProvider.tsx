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
  // Same-origin by default: in production the app is served behind the gateway, so a relative
  // base needs no build-time configuration and cannot point at the ledger by accident.
  return import.meta.env.VITE_GATEWAY_URL ?? `${window.location.origin}/v1`;
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
