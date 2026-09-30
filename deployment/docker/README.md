# docker

```bash
docker build -f deployment/docker/backend.Dockerfile  -t vyoog-api .
docker build -f deployment/docker/frontend.Dockerfile -t vyoog-web .
```

Run from the repository root: the build context must be the root, because the backend image packs the Flyway migrations from `database/migrations` and the frontend image copies `deployment/nginx/frontend.conf`. `.dockerignore` at the root keeps the context small. The frontend image takes `VITE_API_BASE`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM` and `VITE_KEYCLOAK_CLIENT_ID` as build arguments.

These Dockerfiles moved here from `backend/` and `frontend/` (where the context was the module folder). **They have not been built since the move** (the environment that made the change had no Docker daemon). Any external build job that ran `docker build` inside `backend/` or `frontend/` must switch to the commands above.
