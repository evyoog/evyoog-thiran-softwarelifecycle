# Frontend architecture

React 18 + TypeScript (strict) + Vite, TanStack Query for server state, no Redux. The access token is held in memory only; the refresh token is an HttpOnly cookie set by the backend. API types are meant to be generated, not hand-written (VYB-0912 adds the generator). Code layout: `frontend/src/{app,auth,features,shared,styles}`. Conventions and the SPA auth flow: [`../../05-ui/frontend-conventions.md`](../../05-ui/frontend-conventions.md). The frontend README has the run and configuration details: [`../../../frontend/README.md`](../../../frontend/README.md).
