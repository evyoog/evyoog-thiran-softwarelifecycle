#!/usr/bin/env bash
# Local dev launcher for vyoog-api. Loads configuration from the repository-root .env
# (gitignored); copy .env.example to .env first. No credential has a default (docs/DECISIONS.md D22): the
# app refuses to start, naming the variable, unless DB_URL, DB_USER, DB_PASSWORD,
# KEYCLOAK_ROPC_CLIENT_SECRET, KEYCLOAK_IMPERSONATION_CLIENT_SECRET and
# INTERNAL_SSO_SHARED_SECRET are set. Only ever run this against the local
# docker-compose database (`docker compose up -d`), never a shared or live one.
set -euo pipefail
cd "$(dirname "$0")/.."   # repository root: .env lives here

if [[ -f .env ]]; then
  set -a   # export everything .env defines so the child Maven/Java process sees it
  # shellcheck disable=SC1091
  source .env
  set +a
else
  echo "error: .env not found — copy .env.example to .env and fill it in (see README.md)" >&2
  exit 1
fi

case "${DB_URL:-}" in
  *localhost*|*127.0.0.1*) ;;
  *) echo "error: DB_URL must point at localhost when using run-local.sh (got '${DB_URL:-<unset>}')" >&2; exit 1 ;;
esac

cd backend
exec ./mvnw -B -pl vyoog-api spring-boot:run