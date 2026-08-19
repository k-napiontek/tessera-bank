/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of edge/api-gateway, including any path prefix. Never the ledger directly. */
  readonly VITE_GATEWAY_URL: string | undefined;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
