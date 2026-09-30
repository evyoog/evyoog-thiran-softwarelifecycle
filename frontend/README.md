# Vyoog frontend

React 18 + TypeScript (strict) + Vite + TanStack Query. Start with the [root README](../README.md): how to
run the whole system, the configuration and where the plan lives.

```bash
npm ci
npm run dev        # http://localhost:5175, proxies /api to VITE_API_PROXY_TARGET
npx tsc -b         # type check
npm test           # Vitest unit tests
npm run build      # tests, type check, production bundle
```

## Configuration

| Variable | Purpose |
|---|---|
| `VITE_API_BASE` | API base path; `/api/v1` behind the dev proxy or nginx, a full URL for a separate host |
| `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, `VITE_KEYCLOAK_CLIENT_ID` | the shared `eVyoog` realm and the public PKCE client `vyoog-web` |
| `VITE_API_PROXY_TARGET` | dev server only: where `/api` is proxied. Must match the API's `SERVER_PORT` |
| `VITE_SKIP_AUTH` | development only: skips sign-in |

`.env.production` holds the production build settings; `.env.local` overrides them for local development.
Nothing secret belongs in any `VITE_*` variable: Vite ships them in the JavaScript bundle. The access token is held
in memory only, never in `localStorage`.

## Layout

`src/app` shell and routing · `src/auth` sign-in · `src/features` one file or folder per screen ·
`src/shared` API client, UI components · `src/styles` design tokens. Lint (ESLint) and generated API types
are planned for Sprint 2 (VYB-0912).
