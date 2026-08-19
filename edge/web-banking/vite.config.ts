// Vitest owns this file's defineConfig, not Vite's. Vite 8's own signature rejects the `test` key
// outright, and the failure reads as a bad overload rather than a wrong import.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The paths edge/api-gateway routes. Listed rather than proxied wholesale, so that a request this
// application makes to a path the gateway does not serve fails here rather than at the edge.
const GATEWAY_PATHS = ['/accounts', '/transfers', '/holds'];

/**
 * In development the browser talks only to Vite, and Vite talks to the gateway.
 *
 * Not convenience - correctness. The gateway sends no `Access-Control-Allow-Origin` and answers a
 * preflight `OPTIONS` with `401`, because it authenticates every request and in production this
 * application is served behind it, same origin. A dev setup that reached the gateway cross-origin
 * would need CORS the real deployment does not have, and would then be testing an arrangement that
 * does not exist. Proxying keeps development and production the same shape.
 */
const gatewayOrigin = process.env['TB_GATEWAY_ORIGIN'];

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    ...(gatewayOrigin === undefined
      ? {}
      : {
          proxy: Object.fromEntries(
            GATEWAY_PATHS.map((path) => [path, { target: gatewayOrigin, changeOrigin: true }]),
          ),
        }),
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
