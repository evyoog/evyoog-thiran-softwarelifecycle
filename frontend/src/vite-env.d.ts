/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_KEYCLOAK_URL: string
  readonly VITE_KEYCLOAK_REALM: string
  readonly VITE_KEYCLOAK_CLIENT_ID: string
  readonly VITE_API_BASE: string
  /**
   * Local-dev-only escape hatch: skips the Keycloak redirect entirely and renders
   * the shell straight away. Must never be set in `.env.production` — see App.tsx.
   */
  readonly VITE_SKIP_AUTH?: string
}
interface ImportMeta {
  readonly env: ImportMetaEnv
}
