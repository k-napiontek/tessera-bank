import { afterEach, describe, expect, it, vi } from 'vitest';
import { gatewayBaseUrl } from './GatewayProvider';

describe('where the application sends its requests', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('defaults to the same origin with no path prefix at all', () => {
    // `/v1` is the **ledger's** prefix; the gateway adds it itself when forwarding, and serves the
    // contract's own paths at its root. Appending it here is a `no-route` on every request - which
    // no test in this suite can catch, because they all mock whatever base URL they are handed.
    // The live walkthrough caught it, and this is the regression it earned.
    expect(gatewayBaseUrl()).toBe(window.location.origin);
    expect(gatewayBaseUrl()).not.toMatch(/\/v1$/);
  });

  it('uses the configured gateway when one is given', () => {
    vi.stubEnv('VITE_GATEWAY_URL', 'https://gateway.example');
    expect(gatewayBaseUrl()).toBe('https://gateway.example');
  });
});
