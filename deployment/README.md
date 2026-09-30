# deployment

| Path | What |
|---|---|
| [`docker/`](docker/) | `backend.Dockerfile` and `frontend.Dockerfile`; both build from the **repository root** as context |
| [`nginx/`](nginx/) | `frontend.conf`, the nginx config baked into the frontend image (serves the SPA, reverse-proxies `/api/`) |
| [`aws/`](aws/), [`ecs/`](ecs/) | reserved for cloud and container-service definitions; empty, see the READMEs |
| [`environments/`](environments/) | what differs between dev, uat and prod (variable names and sources, never values) |

Local development uses the `docker-compose.yml` at the repository root, not these files. No secret is ever committed here (D22).
