import '@testing-library/jest-dom/vitest';

// jsdom implements neither of these, and both are load-bearing here: the transfer journey mints
// its idempotency key with randomUUID, and the API client aborts a request with a timeout signal.
if (typeof globalThis.crypto === 'undefined') {
  throw new Error('no Web Crypto in the test environment - randomUUID is required');
}
if (typeof globalThis.crypto.randomUUID !== 'function') {
  throw new Error('crypto.randomUUID is missing - the idempotency key cannot be minted');
}
