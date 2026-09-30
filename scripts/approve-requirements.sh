#!/usr/bin/env bash
# Moves DRAFT requirements to APPROVED through the real API — two hops, because
# DRAFT → APPROVED is not a legal transition and never has been.
#
# Through the API on purpose, not straight into Postgres: the endpoints enforce the
# state machine, demand the override reason for a requirement with no acceptance
# criteria, and write an audit event per change. A direct UPDATE would skip all three
# and leave the register saying these requirements approved themselves.
#
# Your password is read with `read -s`, sent once to the backend's own /auth/login,
# and never written to disk or to a log.
#
#   scripts/approve-requirements.sh                  # every DRAFT requirement
#   scripts/approve-requirements.sh VY-1 VY-2        # only these
set -euo pipefail
API=${API:-http://localhost:8080/api/v1}
REASON=${REASON:-"Bulk approval for delivery: submitted without acceptance criteria by explicit override."}

command -v jq >/dev/null || { echo "jq is required: sudo apt install jq" >&2; exit 1; }

read -rp "Keycloak username: " USERNAME
read -rsp "Password: " PASSWORD; echo

TOKEN=$(curl -sS -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg u "$USERNAME" --arg p "$PASSWORD" '{username:$u,password:$p}')" \
  | jq -r '.accessToken // empty')
unset PASSWORD
[ -n "$TOKEN" ] || { echo "Login failed — check the username and password." >&2; exit 1; }
echo "Signed in."

auth=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# Only DRAFT rows: anything already further along needs no help, and asking for a move
# it cannot make would just come back as a skip.
ALL=$(curl -sS "${auth[@]}" "$API/requirements?status=DRAFT&size=500")
if [ $# -gt 0 ]; then
  KEYS=$(printf '%s\n' "$@" | jq -R . | jq -sc .)
  IDS=$(jq -c --argjson keys "$KEYS" '[.content[] | select(.key as $k | $keys | index($k)) | .id]' <<<"$ALL")
else
  IDS=$(jq -c '[.content[].id]' <<<"$ALL")
fi

COUNT=$(jq 'length' <<<"$IDS")
[ "$COUNT" -gt 0 ] || { echo "No DRAFT requirements matched. Nothing to do."; exit 0; }
echo "$COUNT draft requirement(s) to approve."

# Prints applied/skipped and, for anything skipped, the server's own reason.
report() {
  jq -r '"  applied: \([.outcomes[]|select(.applied)]|length)   skipped: \([.outcomes[]|select(.applied|not)]|length)",
         (.outcomes[]|select(.applied|not)|"    skipped — \(.reason)")' <<<"$1"
}

echo "→ Draft to In review…"
STEP1=$(curl -sS -X POST "$API/requirements/bulk-edit" "${auth[@]}" \
  -d "$(jq -nc --argjson ids "$IDS" --arg reason "$REASON" '{ids:$ids,status:"IN_REVIEW",reason:$reason,touchCapability:false}')")
report "$STEP1"

echo "→ In review to Approved…"
STEP2=$(curl -sS -X POST "$API/requirements/bulk-edit" "${auth[@]}" \
  -d "$(jq -nc --argjson ids "$IDS" '{ids:$ids,status:"APPROVED",touchCapability:false}')")
report "$STEP2"

echo
echo "Now approved:"
curl -sS "${auth[@]}" "$API/requirements?status=APPROVED&size=500" | jq -r '"  \(.totalElements) requirement(s)"'
echo "Delivery will now brief them. Undo a step with POST $API/requirements/bulk-edit/<batchId>/undo"
