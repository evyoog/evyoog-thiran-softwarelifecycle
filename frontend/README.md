# Vyoog frontend

React 18 + TypeScript (strict) + Vite + TanStack Query. Start with the [root README](../README.md): how to
run the whole system, the configuration and where the plan lives.

```bash
npm ci
npm run dev        # http://localhost:5175, proxies /api to VITE_API_PROXY_TARGET
npx tsc -b         # type check
npm run lint       # ESLint
npm run generate-api   # regenerate src/shared/api/generated from docs/06-api/openapi/openapi.json
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
`src/shared` API client, UI components · `src/styles` design tokens.

## Lint and API types (VYB-0912)

**Lint.** `npm run lint` runs ESLint (`eslint.config.js`: ESLint recommended, typescript-eslint recommended and the
two classic React hooks rules). It fails on any error and on more than 10 warnings, so a new warning fails it. The 10
that exist are `react-hooks/exhaustive-deps` on `useMemo`/`useEffect` calls; each is a real dependency question to be
answered in the screen it is in, not silenced. The React Compiler rules that `eslint-plugin-react-hooks` 7 also ships
are off (this app does not use the React Compiler); turning them on is its own decision.

**API types.** The backend publishes an OpenAPI document; a copy is committed at
`docs/06-api/openapi/openapi.json` and the backend test `OpenApiDocumentIT` fails if it is stale. `npm run generate-api`
turns it into `src/shared/api/generated/schema.d.ts` (never edit it; CI fails if it is not current). Import from
`@/shared/api/schema` (`Schemas['RequirementView']`). The generated types are **not yet** what the screens use:
the backend does not mark fields required or describe its enums, so every generated field is optional and a status
is a `string`, which would loosen the types the screens rely on. `client.ts` keeps its hand-written types and
`src/shared/api/apiContract.test.ts` checks them against the document (a field the API does not send fails the
build). Moving screens onto the generated types needs the backend DTOs annotated first.

After an API change:

```bash
cd backend && ./mvnw -B -pl vyoog-api -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=OpenApiDocumentIT -Dfailsafe.failIfNoSpecifiedTests=false -Dopenapi.write=true   # rewrites the document
cd ../frontend && npm run generate-api && npm test
```
