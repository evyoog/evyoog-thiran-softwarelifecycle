<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1386–1429). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

## 10. Frontend

### 10.1 Structure

```
src/
├── app/            router, providers, keycloak bootstrap, error boundary
├── shared/
│   ├── ui/         Btn Bdg Card Note Alert Empty Field Modal Seg StatRow Pips Av Bar Page
│   ├── grid/       DataGrid and all its behaviours
│   ├── api/        generated client, query keys, error mapping
│   └── tokens/     design tokens, both themes
└── features/
    ├── home/ mywork/ portfolio/ requirements/ design/
    └── analytics/ quality/ delivery/ releases/ admin/
```

Each feature folder owns its routes, components, hooks and types. Cross-feature imports
go through `shared` only — mirror the backend's module discipline and enforce it with
`eslint-plugin-boundaries`.

### 10.2 Rules

- **TypeScript strict.** Generate API types from the OpenAPI document; never hand-write
  a DTO.
- **TanStack Query** for all server state. No Redux. Local UI state is `useState`.
- **Optimistic updates with rollback** for grid edits and check-offs. Every mutation
  registers its inverse so the undo toast is real.
- Route-level code splitting; the grid and the design canvas are lazy.
- **Virtualise** the grid. Target 50,000 rows without degradation.
- Accessibility: keyboard navigation through the grid, focus traps in modals, visible
  focus rings, `aria-live` for toasts, colour never the sole signal — the coverage pips
  carry letters `U D C T` precisely for this reason.
- The prototype's CSS is the visual specification. Port the tokens verbatim.

### 10.3 Auth in the SPA

`oidc-client-ts` with Authorization Code + PKCE. Access token in memory only —
**never `localStorage`**. Refresh via silent renew in a hidden iframe or refresh-token
rotation. On `401` with `insufficient_user_authentication`, trigger step-up and retry the
original request once.

---
