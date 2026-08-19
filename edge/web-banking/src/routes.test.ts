import { describe, expect, it } from 'vitest';
import { GATEWAY_PREFIXES, ROUTES, statementPath } from './routes';

describe('the paths this application owns', () => {
  it('never collides with a path the gateway routes', () => {
    // The application is served on the gateway's own origin, so any route that shares a prefix
    // with the API is a page that returns JSON on a reload or a shared link. Clicking through the
    // running app hides it completely - client-side routing never asks the server.
    for (const route of Object.values(ROUTES)) {
      for (const prefix of GATEWAY_PREFIXES) {
        expect(route.startsWith(prefix), `${route} collides with ${prefix}`).toBe(false);
      }
    }
  });

  it('builds a statement path outside the gateway namespace', () => {
    expect(statementPath('TB00000000000001')).toBe('/statement/TB00000000000001');
    for (const prefix of GATEWAY_PREFIXES) {
      expect(statementPath('TB00000000000001').startsWith(prefix)).toBe(false);
    }
  });

  it('escapes a reference rather than pasting it into the path', () => {
    expect(statementPath('a/b')).toBe('/statement/a%2Fb');
  });
});
