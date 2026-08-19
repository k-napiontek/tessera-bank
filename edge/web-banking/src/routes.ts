/**
 * The paths this application owns in the browser, kept clear of the ones the gateway owns.
 *
 * In production the application is served **behind** the gateway, on the same origin - which is
 * what lets it call the API without CORS, and what makes any path the gateway routes unusable as a
 * client-side route. `/accounts/{ref}/statement` is a real API path; a browser navigating there
 * gets JSON rather than the application, and clicking a link to it inside the running app appears
 * to work right up until the first reload or shared link.
 *
 * Found the same way: opening the statement URL directly during the live walkthrough returned a
 * problem document in the browser window.
 */

/** Path prefixes the gateway routes. `edge/api-gateway/internal/routing/routing.go`. */
export const GATEWAY_PREFIXES = ['/accounts', '/transfers', '/holds'] as const;

export const ROUTES = {
  dashboard: '/',
  transfer: '/transfer',
  statement: '/statement/:accountRef',
} as const;

export function statementPath(accountRef: string): string {
  return `/statement/${encodeURIComponent(accountRef)}`;
}
