#!/usr/bin/env bash
# Local dev launcher for vyoog-api. Loads secrets from .env (gitignored) so the
# ROPC client secret and OpenAI key stay out of committed config — see
# docs/DECISIONS.md D8 and application.yml's `vyoog.keycloak` comment.
#
# Without these vars the app still starts, but username/password sign-in and the
# six AI advisory paths each refuse with their own named reason.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env ]]; then
  # shellcheck disable=SC1091
  source .env
else
  echo "warning: .env not found — login and AI features will report themselves unconfigured" >&2
fi

exec ./mvnw -B -pl vyoog-api spring-boot:run