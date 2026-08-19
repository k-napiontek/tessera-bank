#!/usr/bin/env node
/**
 * Mint a development token, and the public key that verifies it.
 *
 * **This is a test fixture, not a component.** Nothing in this estate issues tokens: ADR 0007
 * records that the gateway validates a bearer token and deliberately holds no signing key, because
 * an edge component that could mint an identity could mint any identity in the bank. A real
 * deployment puts an identity provider in front of it. This script exists so that the walkthrough
 * in this package's README can be run at all, and it belongs to web-banking's test scaffolding in
 * exactly the way a Testcontainers fixture belongs to a test.
 *
 * Node's own crypto, no dependency. Writes the SPKI public key where TB_GATEWAY_JWT_KEYS wants it
 * and prints the token on stdout.
 *
 *   node scripts/dev-token.mjs --sub CU0000000001 --out /tmp/tessera-jwt-keys.pem
 */

import { createSign, generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const DEFAULTS = {
  sub: 'CU0000000001',
  iss: 'https://issuer.tesserabank.example',
  aud: 'tessera-bank-ledger',
  // The three scopes edge/api-gateway/internal/routing names. Getting these wrong is a 403 that
  // reads exactly like a bad token, so they are copied from the route table rather than guessed.
  scope: 'ledger:read ledger:write accounts:manage',
  ttl: '3600',
  out: '/tmp/tessera-jwt-keys.pem',
};

function parseArgs(argv) {
  const options = { ...DEFAULTS };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index]?.replace(/^--/, '');
    const value = argv[index + 1];
    if (flag === undefined || value === undefined) {
      throw new Error(`expected --flag value, got ${String(argv[index])}`);
    }
    if (!(flag in DEFAULTS)) {
      throw new Error(`unknown flag --${flag}; known flags: ${Object.keys(DEFAULTS).join(', ')}`);
    }
    options[flag] = value;
  }
  return options;
}

const base64url = (input) => Buffer.from(input).toString('base64url');

function main() {
  const options = parseArgs(process.argv.slice(2));

  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  writeFileSync(options.out, publicKey.export({ type: 'spki', format: 'pem' }));

  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'JWT' };
  const claims = {
    sub: options.sub,
    iss: options.iss,
    aud: options.aud,
    scope: options.scope,
    iat: now,
    nbf: now,
    exp: now + Number(options.ttl),
  };

  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
  const signature = createSign('RSA-SHA256').update(signingInput).sign(privateKey).toString('base64url');

  process.stderr.write(`public key written to ${options.out}\n`);
  process.stdout.write(`${signingInput}.${signature}\n`);
}

main();
