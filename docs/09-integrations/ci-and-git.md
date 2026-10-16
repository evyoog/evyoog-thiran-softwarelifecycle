# CI and git: how they feed Vyoog (VYB-0922)

What a CI system or a git host sends to this application, how it shows that it is in use, and where a commit's link comes from. **None of this has been tried against a real CI system, a real git host or the real Keycloak**: the sandbox has none of them, so what is proven is this repository's tests, which call the endpoints with signed test tokens. The shell snippets below are examples to adapt, not tested scripts.

## The way in: three service-account endpoints

Everything a CI system pushes goes to `POST /api/v1/ci/...` as a **registered service account that holds the scope `ci:ingest`** (Administration, Service accounts; its `client_id` is the Keycloak client the CI system uses, and the token's `azp` claim is matched to it). A person's token, a service account without the scope, a client nobody registered, and no token are all refused and nothing is recorded.

| Endpoint | Body | What it records |
|---|---|---|
| `POST /api/v1/ci/commits` | `{"sha": "...", "message": "...", "authorEmail": "..."}` | The commit (once per sha). Every `Requirement: KEY` line in the message becomes an IMPLEMENTS link from the commit to that requirement; an unknown key is reported back, not created. A commit with no such line is kept and shows up in the "untraced commit" gap rule. |
| `POST /api/v1/ci/test-runs` | `{"buildLabel": "...", "source": "ci", "results": [{"testKey": "...", "title": "...", "result": "PASS" or "FAIL", "requirementKeys": ["VY-12"]}]}` | A run (the same build label and source reuse one run) and a result per test against each named requirement, at the requirement's revision as it is now. A test key not seen before becomes a test case. `requirementKeys` may be left out when the test is already linked to requirements. |
| `POST /api/v1/ci/deployments` | `{"environmentId": "<uuid of an environment>", "buildLabel": "...", "succeeded": true, "commitShas": ["..."]}` | A deployment to an environment (Releases, Environments) and which commits it carried. A failed deployment is recorded, not rejected. |

The answer to each says what was linked and what was not, so a CI step can print it.

### Getting a token (example, untested)

The service account's client is a confidential client in the shared `eVyoog` realm; a CI system uses the standard client-credentials grant. Keep the secret in the CI system's secret store, never in the repository.

```sh
TOKEN=$(curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
  -d grant_type=client_credentials -d client_id="$VYOOG_CLIENT_ID" -d client_secret="$VYOOG_CLIENT_SECRET" | jq -r .access_token)
```

### Example: GitHub Actions, on a push (untested)

```yaml
- name: Send the pushed commits to Vyoog
  env: { VYOOG_URL: ${{ vars.VYOOG_URL }}, KEYCLOAK_TOKEN_URL: ${{ vars.KEYCLOAK_TOKEN_URL }},
         VYOOG_CLIENT_ID: ${{ secrets.VYOOG_CLIENT_ID }}, VYOOG_CLIENT_SECRET: ${{ secrets.VYOOG_CLIENT_SECRET }} }
  run: |
    TOKEN=$(curl -sf -X POST "$KEYCLOAK_TOKEN_URL" -d grant_type=client_credentials \
      -d client_id="$VYOOG_CLIENT_ID" -d client_secret="$VYOOG_CLIENT_SECRET" | jq -r .access_token)
    for sha in $(git rev-list "${{ github.event.before }}..${{ github.sha }}"); do
      jq -n --arg sha "$sha" --arg message "$(git log -1 --format=%B "$sha")" --arg email "$(git log -1 --format=%ae "$sha")" \
        '{sha: $sha, message: $message, authorEmail: $email}' |
      curl -sf -X POST "$VYOOG_URL/api/v1/ci/commits" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d @-
    done
```

A commit message names a requirement with a line of its own: `Requirement: VY-12`. (A first push of a new branch has an all-zero `before`; send `git rev-list -n 20 "$GITHUB_SHA"` instead.)

### Example: test results from a JUnit report, in Jenkins or any shell step (untested)

```sh
python3 - "$BUILD_TAG" target/surefire-reports/TEST-*.xml <<'PY' |
import json, sys, glob, xml.etree.ElementTree as ET
build, files = sys.argv[1], [f for p in sys.argv[2:] for f in glob.glob(p)]
results = []
for f in files:
    for case in ET.parse(f).getroot().iter('testcase'):
        if case.find('skipped') is not None:
            continue  # only PASS and FAIL exist here
        failed = case.find('failure') is not None or case.find('error') is not None
        results.append({'testKey': f"{case.get('classname')}.{case.get('name')}", 'title': case.get('name'),
                        'result': 'FAIL' if failed else 'PASS'})
print(json.dumps({'buildLabel': build, 'source': 'ci', 'results': results}))
PY
curl -sf -X POST "$VYOOG_URL/api/v1/ci/test-runs" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d @-
```

This sends no `requirementKeys`: link each test to its requirements first (Requirement detail, Linked work, Link, Test case), or add the keys to the payload. A skipped test is not sent (only PASS and FAIL exist), as the script above does.

## How the connections show it

The registry has a `git` and a `ci` connection (Administration, Connected systems; Connector health). Both read **not connected** until data arrives. The first commit accepted through `/ci/commits` marks `git` connected and healthy and records when; a test run or deployment does the same for `ci`. Connector health then shows "last success" as that time (or the time of the last signed webhook delivery, whichever is later). Nothing here is checked by pinging the CI system or the git host: this application only knows what has been sent to it, so a connection that has gone quiet shows its last success getting older, not an error.

## Commit links

Set the `git` connection's configuration to `{"repoUrl": "https://github.com/<organisation>/<repository>"}` (Administration, Connected systems, git). A commit is then shown with a link to `<repoUrl>/commit/<sha>`:

- in a requirement's **Linked work**, a **Commits** group (newest first, at most 20): the short sha and the commit's first line, hatched and marked "from git" because they are git's words;
- in the **trace graph**, a commit node reads `Commit <short sha> — <first line>` and a click opens it in the repository.

Rules: **https only**, a host, no user name or password, no query or fragment; a trailing slash and a trailing `.git` are dropped. An address that breaks them is refused when it is saved (400, naming why) and the stored one stays. With no address set, or one that cannot be linked to, a commit is still named and simply has no link: never a broken one. The path `/commit/<sha>` is the one GitHub, Gitea and GitLab (which redirects it) use; **Bitbucket and Azure DevOps keep commits elsewhere and are not covered**.

## What is not live

- **The signed webhook route** (`POST /api/v1/webhooks/{key}`) only verifies a delivery's signature and refuses a repeat; it **does not read the payload**. A git host's own push webhook therefore cannot feed commits in, and a CI webhook cannot feed results in. Use a CI step calling the endpoints above. (Reading real git-host payloads would need a stated format per host; not built.)
- Nothing reads a git host's API: only commits that were sent are known, and a commit's author, branch or diff are never fetched.
- Commits sent before a repository address was set get their links as soon as one is: the link is made when it is shown, not when the commit arrives.
