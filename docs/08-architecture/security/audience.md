# Token audience (VYB-0908, F09)

The API accepts a Keycloak access token only if it was issued by the `eVyoog` realm. The realm is shared with
vyg-pms and the pricing tool, so an issuer check alone accepts a token that was minted for one of those apps.
The audience check closes that: when `JWT_AUDIENCE` is set, a token must also carry that value in its `aud` claim.

## Behaviour

| `JWT_AUDIENCE` | Result |
|---|---|
| empty (default) | issuer and expiry only. The API logs a WARN at startup saying the audience is not enforced |
| `vyoog-api` | issuer, expiry **and** `aud` contains `vyoog-api`. A token without it is refused with 401 |

The check is `SecurityConfig.tokenValidator(issuer, audience)`; `JwtAudienceValidationTest` proves it with real
RSA-signed tokens (right audience, wrong audience, no audience, multiple audiences, wrong issuer, expired,
and the check being off).

## Why it is opt-in today

Keycloak does not put the API's name in `aud` by default. Turning the check on before the mapper exists would
refuse every signed-in user. So the code ships the check, and the realm change is made first.

## Enabling it (a Keycloak administrator, once per environment)

Do this in a non-production realm first. Nothing here calls the production Keycloak from the repo or its tests.

1. In the realm, open the client that the frontend signs in with (and any other client that calls this API).
2. *Client scopes* → the client's dedicated scope → *Add mapper* → *By configuration* → **Audience**.
3. Name it `vyoog-api`; *Included Custom Audience* = `vyoog-api`; *Add to access token* = on; save.
4. Sign in and decode the access token: `aud` must now include `vyoog-api`.
5. Set `JWT_AUDIENCE=vyoog-api` in the deployment's secrets and restart. The startup WARN disappears.
6. Service accounts that call the CI endpoint need the same mapper on their client, or they will get 401.

Rolling back is unsetting `JWT_AUDIENCE`.
