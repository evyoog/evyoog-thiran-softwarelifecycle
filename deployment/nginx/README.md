# nginx

`frontend.conf` is copied into the frontend image as `/etc/nginx/conf.d/default.conf`. It serves the built SPA with a client-side-route fallback and reverse-proxies `/api/` to the backend host named in the file; keep that host in step with `VITE_API_PROXY_TARGET`. Rate limits and other nginx hardening are planned in VYB-0909.
