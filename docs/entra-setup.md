# Entra ID setup

How to register Blackhole in Microsoft Entra ID (the Free tier is enough). Doing it needs an Entra
admin: creating app registrations and groups and granting admin consent are admin actions.

Blackhole checks the token itself; Entra only proves who the person is and which groups they are in.
Humans get their role from the `groups` claim of a delegated token. The Velocity proxy, the Discord bot and the
metrics scraper use app-only tokens whose `roles` claim carries an app role. A tenant member in no
mapped group is signed in but has no role, so every protected route answers 403.

## What gets created

| Object | Name (`<env>` is `dev` or `prod`) | Purpose |
|--------|-----------------------------------|---------|
| API registration | `Blackhole API (<env>)` | The audience of every token. Exposes the scope `access_as_user` and the app roles `Blackhole.Proxy`, `Blackhole.Metrics` and `Blackhole.Discord`. |
| SPA registration | `Blackhole Admin UI (<env>)` | The admin UI: Authorization Code flow with PKCE, no secret. |
| Velocity registration | `Blackhole Velocity (<env>)` | The proxy plugin: client credentials, holds `Blackhole.Proxy`. |
| Discord bot registration | `Blackhole Discord (<env>)` | The Discord bot (`discord` module): client credentials, holds `Blackhole.Discord`, which allows only the `/discord/**` routes. |
| Security groups | `sg-blackhole-<env>-admin`, `-edit`, `-view`, `-ops` | `ADMIN`, `MODERATOR`, `VIEWER`, `METRICS` (a person who may read `/prometheus`). |

Settings that matter on the API registration (manifest):

- `requestedAccessTokenVersion: 2` (shown as `accessTokenAcceptedVersion` in the portal manifest).
  Without it Entra issues v1 tokens with another issuer and audience and the backend refuses them.
- `groupMembershipClaims: "SecurityGroup"`, so delegated tokens carry the `groups` claim.
- App roles `Blackhole.Proxy`, `Blackhole.Metrics` and `Blackhole.Discord`, allowed member type **Applications** only.
- Application ID URI `api://<api-client-id>` and the delegated scope `access_as_user`.
- "Assignment required" stays **off** on the API's enterprise application: any tenant member can get a
  token, and the backend denies everyone without a mapped group.

The SPA registration is a **Single-page application** with the redirect URIs
`<ui-origin>/auth/callback` and `<ui-origin>/` (and `http://localhost:5173/auth/callback` for
development), the delegated permission `access_as_user` of the API, and admin consent for it.

The Velocity registration has the application permission `Blackhole.Proxy` of the API with admin
consent, and a client secret. The Discord bot registration is the same with `Blackhole.Discord`.

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
`Blackhole.Proxy` to the Velocity registration and `Blackhole.Discord` to the Discord bot
registration, and creates a one-year secret for each when the env file has none. Each secret is
saved to the env file right after it is created, so a later failure does not lose it. The
production origin must start with `https://`; the script refuses anything else before it creates
anything. `--dry-run` prints what would be created or changed and makes no Azure call and no file
write.

**Production step: user assignment.** The script sets `appRoleAssignmentRequired` to `true` on the
service principal of `Blackhole Admin UI (<env>)` (Enterprise applications, Properties, "Assignment
required"), so only assigned users and groups can sign in to the admin UI. It first assigns the
signed-in admin, so the person running it is not locked out. Assign the staff afterwards under
Enterprise applications, Blackhole Admin UI, Users and groups (assigning groups needs Entra ID P1;
otherwise assign users). Assignment only decides who can get a token; the group claims still decide
what they may do. The env file (mode 600) contains tenant, client and group ids and the secret: it
is a secret file, keep it out of git and out of chat. A rerun rewrites the lines the script manages and keeps every other line of the file (for example `BLACKHOLE_BANID_KEY` or `DISCORD_TOKEN`). Add staff to the three role groups afterwards
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
| `BLACKHOLE_DISCORD_TENANT_ID`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_AUTH_SCOPE` | Discord bot (`discord` module) |
| `BLACKHOLE_METRICS_CLIENT_ID`, `_CLIENT_SECRET` | Prometheus scraper |

The backend maps the Discord bot's role through `BLACKHOLE_AUTH_MACHINE_ROLE_DISCORD` (default
`Blackhole.Discord`), next to `BLACKHOLE_AUTH_MACHINE_ROLE_PROXY` and `_METRICS`.

`BLACKHOLE_AUTH_AUDIENCE` is the API's application (client) id and `BLACKHOLE_AUTH_SCOPE` is
`api://<api-client-id>/.default`.

## Metrics scraper

Prometheus authenticates as an application holding `Blackhole.Metrics`. The script creates the
registration `Blackhole Metrics (<env>)`, assigns `Blackhole.Metrics` to its service principal
(only when the assignment is missing) and writes `BLACKHOLE_METRICS_CLIENT_ID` and
`BLACKHOLE_METRICS_CLIENT_SECRET` to the env file. Nothing is manual here; give the scraper that
client id and secret.

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
