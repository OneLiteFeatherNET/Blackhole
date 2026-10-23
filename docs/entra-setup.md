# Entra ID setup

How to register Blackhole in Microsoft Entra ID (the Free tier is enough). Doing it needs an Entra
admin: creating app registrations and groups and granting admin consent are admin actions.

Blackhole checks the token itself; Entra only proves who the person is and which groups they are in.
Humans get their role from the `groups` claim of a delegated token. The Velocity proxy and the
metrics scraper use app-only tokens whose `roles` claim carries an app role. A tenant member in no
mapped group is signed in but has no role, so every protected route answers 403.

## What gets created

| Object | Name (`<env>` is `dev` or `prod`) | Purpose |
|--------|-----------------------------------|---------|
| API registration | `Blackhole API (<env>)` | The audience of every token. Exposes the scope `access_as_user` and the app roles `Blackhole.Proxy` and `Blackhole.Metrics`. |
| SPA registration | `Blackhole Admin UI (<env>)` | The admin UI: Authorization Code flow with PKCE, no secret. |
| Velocity registration | `Blackhole Velocity (<env>)` | The proxy plugin: client credentials, holds `Blackhole.Proxy`. |
| Security groups | `sg-blackhole-<env>-admin`, `-edit`, `-view`, `-ops` | `ADMIN`, `MODERATOR`, `VIEWER`, `METRICS` (a person who may read `/prometheus`). |

Settings that matter on the API registration (manifest):

- `requestedAccessTokenVersion: 2` (shown as `accessTokenAcceptedVersion` in the portal manifest).
  Without it Entra issues v1 tokens with another issuer and audience and the backend refuses them.
- `groupMembershipClaims: "SecurityGroup"`, so delegated tokens carry the `groups` claim.
- App roles `Blackhole.Proxy` and `Blackhole.Metrics`, allowed member type **Applications** only.
- Application ID URI `api://<api-client-id>` and the delegated scope `access_as_user`.
- "Assignment required" stays **off** on the API's enterprise application: any tenant member can get a
  token, and the backend denies everyone without a mapped group.

The SPA registration is a **Single-page application** with the redirect URIs
`<ui-origin>/auth/callback` and `<ui-origin>/` (and `http://localhost:5173/auth/callback` for
development), the delegated permission `access_as_user` of the API, and admin consent for it.

The Velocity registration has the application permission `Blackhole.Proxy` of the API with admin
consent, and a client secret.

## Run the script

`docs/entra-setup.sh` creates all of this and is safe to run again. It needs the Azure CLI and
`uuidgen`; sign in with `az login` as an Entra admin first.

```shell
# development: UI on http://localhost:5173, writes ~/.config/blackhole/entra-dev.env
docs/entra-setup.sh dev

# production: pass the origin the UI is served from and, optionally, the env file path
docs/entra-setup.sh prod https://blackhole.example.org ~/.config/blackhole/entra-prod.env
```

It adds the signed-in admin to the `-admin` group, grants tenant-wide consent for the SPA, assigns
`Blackhole.Proxy` to the Velocity registration and creates a one-year Velocity secret when the env
file has none. The env file (mode 600) contains tenant, client and group ids and the secret: it
is a secret file, keep it out of git and out of chat. Add staff to the three role groups afterwards
(group membership is not scripted).

### Where each value goes

Tokens carry group **object ids**, not names; the script writes the ids of the four groups into the
`BLACKHOLE_AUTH_ROLE_*` lines. If you create the groups by hand, copy their object ids from the
portal (Groups, the group, Object Id).

| Env file line | Used by |
|---------------|---------|
| `BLACKHOLE_AUTH_TENANT_ID`, `_AUDIENCE`, `_ISSUER`, `_JWKS_URL`, `_ROLE_ADMIN`, `_ROLE_MODERATOR`, `_ROLE_VIEWER`, `_ROLE_METRICS` | Backend |
| `BLACKHOLE_UI_OIDC_AUTHORITY`, `_OIDC_CLIENT_ID`, `_API_SCOPE` | Web container |
| `BLACKHOLE_TENANT_ID`, `BLACKHOLE_CLIENT_ID`, `BLACKHOLE_CLIENT_SECRET`, `BLACKHOLE_AUTH_SCOPE` | Velocity plugin |

`BLACKHOLE_AUTH_AUDIENCE` is the API's application (client) id and `BLACKHOLE_AUTH_SCOPE` is
`api://<api-client-id>/.default`.

## Metrics scraper (optional)

Prometheus authenticates as an application holding `Blackhole.Metrics`. The script does not create it;
do the same as for Velocity with another registration, for example:

```shell
az ad app create --display-name "Blackhole Metrics (<env>)" --sign-in-audience AzureADMyOrg
# add the application permission Blackhole.Metrics of the API, then grant admin consent
az ad app credential reset --id <metrics-client-id> --display-name <env> --years 1 --append
```

The scraper requests a token with the scope `api://<api-client-id>/.default` and sends it as
`Authorization: Bearer` to `GET /prometheus`.

## Rotating a client secret

Secrets expire (the script uses one year). Rotate without downtime, for the Velocity registration
(the same works for the metrics scraper):

1. Create a new secret next to the old one:
   `az ad app credential reset --id <velocity-client-id> --display-name <env>-<year> --years 1 --append --query password -o tsv`
2. Put it into `BLACKHOLE_CLIENT_SECRET` (or `auth.clientSecret` in the plugin's `config.json`) and
   restart the proxy.
3. Check the proxy still applies a punishment, then delete the old secret:
   `az ad app credential list --id <velocity-client-id>` and
   `az ad app credential delete --id <velocity-client-id> --key-id <old-key-id>`.

Do not re-run the script to rotate: it keeps the secret already in the env file.

## Upgrading to Entra ID P1/P2 later

On the Free tier roles come from groups. With P1 you can assign groups to **app roles** instead
(`Admin`, `Moderator`, ...) and read the `roles` claim. This is a configuration change only:
set `BLACKHOLE_AUTH_ROLES_CLAIM_HUMAN=roles` and the `BLACKHOLE_AUTH_ROLE_*` variables to the app
role values instead of group object ids.

## Checking it works

Sign in to the UI as a member of one role group: the top bar shows "Signed in as ..." and
`GET /auth/me` lists the role. A tenant member in no group lands on the "no access" page. Group
changes take effect when the access token expires (typically 60 to 90 minutes).
