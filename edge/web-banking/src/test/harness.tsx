/**
 * One place that renders a screen inside a signed-in session against a mocked gateway.
 *
 * Every screen needs the same three things - a router, a client pointed at the mock and the
 * account references the session was given - and repeating that arrangement in each test file is
 * how it quietly drifts apart between them.
 */

import { render } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { GatewayProvider } from '../api/GatewayProvider';
import type { GatewayClient } from '../api/client';
import { createClient } from '../api/client';

export const GATEWAY = 'https://gateway.test/v1';
export const TOKEN = 'a-test-token';
export const ACCOUNT_ONE = 'TB00000000000001';
export const ACCOUNT_TWO = 'TB00000000000002';

export function testClient(): GatewayClient {
  return createClient({ baseUrl: GATEWAY, token: TOKEN });
}

export function renderSignedIn(
  ui: ReactNode,
  options: { accountRefs?: readonly string[]; client?: GatewayClient } = {},
): RenderResult {
  return render(
    <MemoryRouter>
      <GatewayProvider
        client={options.client ?? testClient()}
        accountRefs={options.accountRefs ?? [ACCOUNT_ONE, ACCOUNT_TWO]}
      >
        {ui}
      </GatewayProvider>
    </MemoryRouter>,
  );
}

/** An account document as the gateway relays it, with both balances overridable. */
export function accountDocument(
  accountRef: string,
  booked: number,
  available: number,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    accountRef,
    customerRef: 'CU0000000001',
    accountType: 'LIABILITY',
    currency: 'PLN',
    status: 'OPEN',
    bookedBalance: { amountMinor: booked, currency: 'PLN' },
    availableBalance: { amountMinor: available, currency: 'PLN' },
    openedDate: '2026-01-04',
    lastMovementDate: '2026-08-18',
    ...overrides,
  };
}
