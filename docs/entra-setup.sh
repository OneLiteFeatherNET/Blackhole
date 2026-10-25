#!/bin/bash
# Creates the Blackhole Entra set for one environment (idempotent): 4 security groups and the
# API, SPA (admin UI), Velocity and Discord bot app registrations, with role claims, scopes and
# admin consent. Needs the Azure CLI (az login as a user who may create app registrations and groups and grant
# admin consent) and uuidgen. Safe to run again: existing objects are reused and the Velocity
# secrets (Velocity, Discord bot) are only created when the env file has none.
#
# Usage: docs/entra-setup.sh [--dry-run] <dev|prod> [redirect-origin] [env-file]
#   --dry-run        (or DRY_RUN=1) Prints every az command that would create or change something
#                    and makes no real az call and no file write. Use it to check the arguments.
#   redirect-origin  Where the admin UI is served, no trailing slash. Default for dev:
#                    http://localhost:5173. Required for prod and must start with https://, e.g.
#                    https://blackhole.example.org (dev may use http).
#   env-file         Where the ids and the client secrets are written (mode 600). Default:
#                    ~/.config/blackhole/entra-<env>.env. Never commit that file.
# See docs/entra-setup.md.
set -euo pipefail

# write_env FILE: writes the managed lines to FILE (mode 600) from the caller's variables
# (ENVNAME, TENANT, API, SPA, VEL, BOT, G_*, VEL_SECRET, BOT_SECRET), then appends every line of
# the old FILE whose key the script does not manage. The managed keys are read from the block
# just written, so they cannot drift from it. Secrets already in FILE are reused when the
# corresponding *_SECRET variable is empty.
write_env() {
  local out="$1" block keys
  umask 077
  block=$({
  echo "# Blackhole Entra $ENVNAME set, tenant $TENANT. Secret file: do not commit or share."
  echo "BLACKHOLE_AUTH_TENANT_ID=$TENANT"
  echo "BLACKHOLE_AUTH_AUDIENCE=$API"
  echo "BLACKHOLE_AUTH_ISSUER=https://login.microsoftonline.com/$TENANT/v2.0"
  echo "BLACKHOLE_AUTH_JWKS_URL=https://login.microsoftonline.com/$TENANT/discovery/v2.0/keys"
  echo "BLACKHOLE_AUTH_ROLE_ADMIN=$G_ADMIN"
  echo "BLACKHOLE_AUTH_ROLE_MODERATOR=$G_EDIT"
  echo "BLACKHOLE_AUTH_ROLE_VIEWER=$G_VIEW"
  echo "BLACKHOLE_AUTH_ROLE_METRICS=$G_OPS"
  echo "BLACKHOLE_UI_OIDC_AUTHORITY=https://login.microsoftonline.com/$TENANT/v2.0"
  echo "BLACKHOLE_UI_OIDC_CLIENT_ID=$SPA"
  echo "BLACKHOLE_UI_API_SCOPE=api://$API/access_as_user"
  echo "BLACKHOLE_TENANT_ID=$TENANT"
  echo "BLACKHOLE_CLIENT_ID=$VEL"
  echo "BLACKHOLE_AUTH_SCOPE=api://$API/.default"
  if [ -n "$VEL_SECRET" ]; then echo "BLACKHOLE_CLIENT_SECRET=$VEL_SECRET";
  else grep '^BLACKHOLE_CLIENT_SECRET=' "$out"; fi
  echo "BLACKHOLE_DISCORD_TENANT_ID=$TENANT"
  echo "BLACKHOLE_DISCORD_CLIENT_ID=$BOT"
  echo "BLACKHOLE_DISCORD_AUTH_SCOPE=api://$API/.default"
  if [ -n "$BOT_SECRET" ]; then echo "BLACKHOLE_DISCORD_CLIENT_SECRET=$BOT_SECRET";
  else grep '^BLACKHOLE_DISCORD_CLIENT_SECRET=' "$out"; fi
  if [ -n "${METRICS:-}" ]; then
    echo "BLACKHOLE_METRICS_CLIENT_ID=$METRICS"
    if [ -n "${METRICS_SECRET:-}" ]; then echo "BLACKHOLE_METRICS_CLIENT_SECRET=$METRICS_SECRET";
    else grep '^BLACKHOLE_METRICS_CLIENT_SECRET=' "$out" || true; fi
  fi
  })
  keys=$(printf '%s\n' "$block" | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' | tr '\n' ' ')
  {
    printf '%s\n' "$block"
    if [ -f "$out" ]; then
      awk -v keys="$keys" 'BEGIN { n = split(keys, a, " "); for (i = 1; i <= n; i++) managed[a[i]] = 1 }
        /^[A-Za-z_][A-Za-z0-9_]*=/ { k = $0; sub(/=.*/, "", k); if (!(k in managed)) print }' "$out"
    fi
  } > "$out.new"
  chmod 600 "$out.new"
  mv "$out.new" "$out"
}

# save_secret FILE KEY VALUE: stores one KEY=VALUE line in FILE (mode 600), replacing an existing
# line for KEY and keeping every other line. Called right after a secret is created, so a failure
# later in the run cannot lose it.
save_secret() {
  local out="$1" key="$2" value="$3"
  umask 077
  {
    if [ -f "$out" ]; then grep -v "^$key=" "$out" || true; fi
    printf '%s=%s\n' "$key" "$value"
  } > "$out.new"
  chmod 600 "$out.new"
  mv "$out.new" "$out"
}

# Sourced by the test (docs/entra-setup.test.sh): stop here, before any az call.
if [ "${BASH_SOURCE[0]}" != "$0" ]; then return 0; fi

DRY_RUN="${DRY_RUN:-}"
ARGS=()
for a in "$@"; do
  if [ "$a" = "--dry-run" ]; then DRY_RUN=1; else ARGS+=("$a"); fi
done
set -- ${ARGS[@]+"${ARGS[@]}"}

# In a dry run az is replaced: reads return nothing (or a placeholder id), anything that would
# create or change a resource is printed to stderr and not run.
az() {
  if [ "$DRY_RUN" != 1 ]; then command az "$@"; return; fi
  case "$*" in
    "account show"*) echo "dry-run-tenant";;
    "ad signed-in-user show"*) echo "dry-run-user";;
    "ad app show"*|"ad sp show"*) echo "dry-run-id";;
    "rest --method GET"*"length(@)"*) echo 0;;
    "ad group list"*|"ad app list"*|"ad group member check"*|"rest --method GET"*) ;;
    "ad app credential reset"*) echo "[dry-run] az ad app credential reset (secret not shown)" >&2
      echo "dry-run-secret";;
    "rest --method"*) echo "[dry-run] az rest ${*:2:4}" >&2; cat > /dev/null 2>&1 || true;;
    *create*) echo "[dry-run] az $*" >&2; echo "dry-run-id";;
    *) echo "[dry-run] az $*" >&2;;
  esac
}

ENVNAME="${1:-}"
case "$ENVNAME" in dev|prod) ;; *) echo "usage: $0 [--dry-run] <dev|prod> [redirect-origin] [env-file]" >&2; exit 2;; esac
if [ -n "${2:-}" ]; then ORIGIN="${2%/}"
elif [ "$ENVNAME" = dev ]; then ORIGIN="http://localhost:5173"
else echo "prod needs a redirect origin, e.g. https://blackhole.example.org" >&2; exit 2; fi
if [ "$ENVNAME" = prod ]; then
  case "$ORIGIN" in
    https://?*) ;;
    *) echo "prod redirect origin must start with https://, got: $ORIGIN" >&2; exit 2;;
  esac
fi
OUT="${3:-$HOME/.config/blackhole/entra-$ENVNAME.env}"

# Fail before creating anything when a required tool or value is missing.
command -v uuidgen > /dev/null || { echo "uuidgen is required" >&2; exit 1; }
if [ "$DRY_RUN" != 1 ]; then
  command -v az > /dev/null || { echo "the Azure CLI (az) is required" >&2; exit 1; }
  mkdir -p "$(dirname "$OUT")"
fi
TENANT=$(az account show --query tenantId -o tsv)
ME=$(az ad signed-in-user show --query id -o tsv)
[ -n "$TENANT" ] || { echo "no tenant id: run az login first" >&2; exit 1; }
[ -n "$ME" ] || { echo "no signed-in user: run az login as a user, not a service principal" >&2; exit 1; }
GRAPH=https://graph.microsoft.com/v1.0

group() { # name -> id
  local id
  id=$(az ad group list --filter "displayName eq '$1'" --query "[0].id" -o tsv)
  [ -n "$id" ] || id=$(az ad group create --display-name "$1" --mail-nickname "$1" \
      --description "Blackhole $ENVNAME: $2" --query id -o tsv)
  echo "$id"
}
app() { # displayName -> appId
  local id
  id=$(az ad app list --filter "displayName eq '$1'" --query "[0].appId" -o tsv)
  [ -n "$id" ] || id=$(az ad app create --display-name "$1" --sign-in-audience AzureADMyOrg --query appId -o tsv)
  echo "$id"
}
sp() { az ad sp show --id "$1" --query id -o tsv 2>/dev/null || az ad sp create --id "$1" --query id -o tsv; }

G_ADMIN=$(group sg-blackhole-$ENVNAME-admin "admin")
G_EDIT=$(group sg-blackhole-$ENVNAME-edit "moderation")
G_VIEW=$(group sg-blackhole-$ENVNAME-view "read only")
G_OPS=$(group sg-blackhole-$ENVNAME-ops "metrics")
az ad group member check --group "$G_ADMIN" --member-id "$ME" --query value -o tsv | grep -q true \
  || az ad group member add --group "$G_ADMIN" --member-id "$ME"

# --- API ---
API=$(app "Blackhole API ($ENVNAME)")
API_OBJ=$(az ad app show --id "$API" --query id -o tsv)
SCOPE_ID=$(az ad app show --id "$API" --query "api.oauth2PermissionScopes[?value=='access_as_user'].id | [0]" -o tsv)
[ -n "$SCOPE_ID" ] || SCOPE_ID=$(uuidgen)
PROXY_ID=$(az ad app show --id "$API" --query "appRoles[?value=='Blackhole.Proxy'].id | [0]" -o tsv)
[ -n "$PROXY_ID" ] || PROXY_ID=$(uuidgen)
METRICS_ID=$(az ad app show --id "$API" --query "appRoles[?value=='Blackhole.Metrics'].id | [0]" -o tsv)
[ -n "$METRICS_ID" ] || METRICS_ID=$(uuidgen)
DISCORD_ID=$(az ad app show --id "$API" --query "appRoles[?value=='Blackhole.Discord'].id | [0]" -o tsv)
[ -n "$DISCORD_ID" ] || DISCORD_ID=$(uuidgen)
az rest --method PATCH --url "$GRAPH/applications/$API_OBJ" --headers Content-Type=application/json --body @- <<EOF
{
  "identifierUris": ["api://$API"],
  "groupMembershipClaims": "SecurityGroup",
  "api": {
    "requestedAccessTokenVersion": 2,
    "oauth2PermissionScopes": [{
      "id": "$SCOPE_ID", "value": "access_as_user", "type": "User", "isEnabled": true,
      "adminConsentDisplayName": "Use Blackhole as the signed-in staff member",
      "adminConsentDescription": "Lets the Blackhole admin UI call the API on behalf of the signed-in staff member.",
      "userConsentDisplayName": "Use Blackhole as you",
      "userConsentDescription": "Lets the Blackhole admin UI call the API on your behalf."
    }]
  },
  "appRoles": [
    {"id": "$PROXY_ID", "value": "Blackhole.Proxy", "displayName": "Blackhole proxy",
     "description": "Velocity proxy plugin acting for staff.", "allowedMemberTypes": ["Application"], "isEnabled": true},
    {"id": "$METRICS_ID", "value": "Blackhole.Metrics", "displayName": "Blackhole metrics",
     "description": "Prometheus scraper.", "allowedMemberTypes": ["Application"], "isEnabled": true},
    {"id": "$DISCORD_ID", "value": "Blackhole.Discord", "displayName": "Blackhole Discord bot",
     "description": "Discord bot: account linking and team notifications.", "allowedMemberTypes": ["Application"], "isEnabled": true}
  ]
}
EOF
API_SP=$(sp "$API")

# --- SPA (admin UI) ---
SPA=$(app "Blackhole Admin UI ($ENVNAME)")
SPA_OBJ=$(az ad app show --id "$SPA" --query id -o tsv)
REDIRECTS="\"$ORIGIN/auth/callback\", \"$ORIGIN/\""
case "$ORIGIN" in http://localhost:*) # local development also allows the loopback address form
  REDIRECTS="$REDIRECTS, \"http://127.0.0.1:${ORIGIN##*:}/auth/callback\"";;
esac
az rest --method PATCH --url "$GRAPH/applications/$SPA_OBJ" --headers Content-Type=application/json --body @- <<EOF
{
  "spa": {"redirectUris": [$REDIRECTS]},
  "requiredResourceAccess": [
    {"resourceAppId": "$API", "resourceAccess": [{"id": "$SCOPE_ID", "type": "Scope"}]},
    {"resourceAppId": "00000003-0000-0000-c000-000000000000", "resourceAccess": [
      {"id": "37f7f235-527c-4136-accd-4a02d197296e", "type": "Scope"},
      {"id": "14dad69e-099b-42c9-810b-d002981feec1", "type": "Scope"},
      {"id": "7427e0e9-2fba-42fe-b0c0-848c9e6a8182", "type": "Scope"}]}
  ]
}
EOF
SPA_SP=$(sp "$SPA")
# Only users and groups assigned to the Admin UI app can sign in to it. Assign the signed-in admin
# first, so the switch cannot lock out the person running the script (default access role).
if [ "$(az rest --method GET --url "$GRAPH/servicePrincipals/$SPA_SP" --query appRoleAssignmentRequired -o tsv)" != "true" ]; then
  mine=$(az rest --method GET --url "$GRAPH/servicePrincipals/$SPA_SP/appRoleAssignedTo" --query "value[?principalId=='$ME'] | length(@)" -o tsv)
  [ "${mine:-0}" != "0" ] || az rest --method POST --url "$GRAPH/servicePrincipals/$SPA_SP/appRoleAssignedTo" \
    --headers Content-Type=application/json \
    --body "{\"principalId\":\"$ME\",\"resourceId\":\"$SPA_SP\",\"appRoleId\":\"00000000-0000-0000-0000-000000000000\"}" > /dev/null
  az rest --method PATCH --url "$GRAPH/servicePrincipals/$SPA_SP" --headers Content-Type=application/json \
    --body '{"appRoleAssignmentRequired":true}' > /dev/null
fi
# tenant-wide consent for the SPA's delegated scopes (api access_as_user + openid/profile/offline_access)
for pair in "$API_SP:access_as_user" "$(az ad sp show --id 00000003-0000-0000-c000-000000000000 --query id -o tsv):openid profile offline_access"; do
  res=${pair%%:*}; scopes=${pair#*:}
  existing=$(az rest --method GET --url "$GRAPH/oauth2PermissionGrants?\$filter=clientId eq '$SPA_SP' and resourceId eq '$res'" --query "value[0].id" -o tsv)
  if [ -z "$existing" ]; then
    az rest --method POST --url "$GRAPH/oauth2PermissionGrants" --headers Content-Type=application/json \
      --body "{\"clientId\":\"$SPA_SP\",\"consentType\":\"AllPrincipals\",\"resourceId\":\"$res\",\"scope\":\"$scopes\"}" > /dev/null
  fi
done

# --- Velocity (client credentials, Blackhole.Proxy) ---
VEL=$(app "Blackhole Velocity ($ENVNAME)")
VEL_OBJ=$(az ad app show --id "$VEL" --query id -o tsv)
az rest --method PATCH --url "$GRAPH/applications/$VEL_OBJ" --headers Content-Type=application/json \
  --body "{\"requiredResourceAccess\":[{\"resourceAppId\":\"$API\",\"resourceAccess\":[{\"id\":\"$PROXY_ID\",\"type\":\"Role\"}]}]}"
VEL_SP=$(sp "$VEL")
has=$(az rest --method GET --url "$GRAPH/servicePrincipals/$VEL_SP/appRoleAssignments" --query "value[?appRoleId=='$PROXY_ID'] | length(@)" -o tsv)
[ "$has" != "0" ] || az rest --method POST --url "$GRAPH/servicePrincipals/$VEL_SP/appRoleAssignments" \
  --headers Content-Type=application/json \
  --body "{\"principalId\":\"$VEL_SP\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"$PROXY_ID\"}" > /dev/null
VEL_SECRET=""
if ! grep -q '^BLACKHOLE_CLIENT_SECRET=' "$OUT" 2>/dev/null; then
  VEL_SECRET=$(az ad app credential reset --id "$VEL" --display-name "$ENVNAME" --years 1 --append --query password -o tsv)
  [ "$DRY_RUN" = 1 ] || save_secret "$OUT" BLACKHOLE_CLIENT_SECRET "$VEL_SECRET"
fi

# --- Discord bot (client credentials, Blackhole.Discord) ---
BOT=$(app "Blackhole Discord ($ENVNAME)")
BOT_OBJ=$(az ad app show --id "$BOT" --query id -o tsv)
az rest --method PATCH --url "$GRAPH/applications/$BOT_OBJ" --headers Content-Type=application/json \
  --body "{\"requiredResourceAccess\":[{\"resourceAppId\":\"$API\",\"resourceAccess\":[{\"id\":\"$DISCORD_ID\",\"type\":\"Role\"}]}]}"
BOT_SP=$(sp "$BOT")
has=$(az rest --method GET --url "$GRAPH/servicePrincipals/$BOT_SP/appRoleAssignments" --query "value[?appRoleId=='$DISCORD_ID'] | length(@)" -o tsv)
[ "$has" != "0" ] || az rest --method POST --url "$GRAPH/servicePrincipals/$BOT_SP/appRoleAssignments" \
  --headers Content-Type=application/json \
  --body "{\"principalId\":\"$BOT_SP\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"$DISCORD_ID\"}" > /dev/null
BOT_SECRET=""
if ! grep -q '^BLACKHOLE_DISCORD_CLIENT_SECRET=' "$OUT" 2>/dev/null; then
  BOT_SECRET=$(az ad app credential reset --id "$BOT" --display-name "$ENVNAME" --years 1 --append --query password -o tsv)
  [ "$DRY_RUN" = 1 ] || save_secret "$OUT" BLACKHOLE_DISCORD_CLIENT_SECRET "$BOT_SECRET"
fi

# --- Metrics scraper (client credentials, Blackhole.Metrics) ---
METRICS=$(app "Blackhole Metrics ($ENVNAME)")
METRICS_OBJ=$(az ad app show --id "$METRICS" --query id -o tsv)
az rest --method PATCH --url "$GRAPH/applications/$METRICS_OBJ" --headers Content-Type=application/json \
  --body "{\"requiredResourceAccess\":[{\"resourceAppId\":\"$API\",\"resourceAccess\":[{\"id\":\"$METRICS_ID\",\"type\":\"Role\"}]}]}"
METRICS_SP=$(sp "$METRICS")
has=$(az rest --method GET --url "$GRAPH/servicePrincipals/$METRICS_SP/appRoleAssignments" --query "value[?appRoleId=='$METRICS_ID'] | length(@)" -o tsv)
[ "$has" != "0" ] || az rest --method POST --url "$GRAPH/servicePrincipals/$METRICS_SP/appRoleAssignments" \
  --headers Content-Type=application/json \
  --body "{\"principalId\":\"$METRICS_SP\",\"resourceId\":\"$API_SP\",\"appRoleId\":\"$METRICS_ID\"}" > /dev/null
METRICS_SECRET=""
if ! grep -q '^BLACKHOLE_METRICS_CLIENT_SECRET=' "$OUT" 2>/dev/null; then
  METRICS_SECRET=$(az ad app credential reset --id "$METRICS" --display-name "$ENVNAME" --years 1 --append --query password -o tsv)
  [ "$DRY_RUN" = 1 ] || save_secret "$OUT" BLACKHOLE_METRICS_CLIENT_SECRET "$METRICS_SECRET"
fi

if [ "$DRY_RUN" = 1 ]; then echo "dry run: nothing was created or written"; exit 0; fi
write_env "$OUT"
echo "done; ids in $OUT"
