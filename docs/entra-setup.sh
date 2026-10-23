#!/bin/bash
# Creates the Blackhole Entra set for one environment (idempotent): 4 security groups and the
# API, SPA (admin UI) and Velocity app registrations, with role claims, scopes and admin consent.
# Needs the Azure CLI (az login as a user who may create app registrations and groups and grant
# admin consent) and uuidgen. Safe to run again: existing objects are reused and the Velocity
# secret is only created when the env file has none.
#
# Usage: docs/entra-setup.sh <dev|prod> [redirect-origin] [env-file]
#   redirect-origin  Where the admin UI is served, no trailing slash. Default for dev:
#                    http://localhost:5173. Required for prod, e.g. https://blackhole.example.org.
#   env-file         Where the ids and the Velocity secret are written (mode 600). Default:
#                    ~/.config/blackhole/entra-<env>.env. Never commit that file.
# See docs/entra-setup.md.
set -euo pipefail
ENVNAME="${1:-}"
case "$ENVNAME" in dev|prod) ;; *) echo "usage: $0 <dev|prod> [redirect-origin] [env-file]" >&2; exit 2;; esac
if [ -n "${2:-}" ]; then ORIGIN="${2%/}"
elif [ "$ENVNAME" = dev ]; then ORIGIN="http://localhost:5173"
else echo "prod needs a redirect origin, e.g. https://blackhole.example.org" >&2; exit 2; fi
OUT="${3:-$HOME/.config/blackhole/entra-$ENVNAME.env}"
mkdir -p "$(dirname "$OUT")"
TENANT=$(az account show --query tenantId -o tsv)
ME=$(az ad signed-in-user show --query id -o tsv)
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
     "description": "Prometheus scraper.", "allowedMemberTypes": ["Application"], "isEnabled": true}
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
fi

umask 077
{
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
  else grep '^BLACKHOLE_CLIENT_SECRET=' "$OUT"; fi
} > "$OUT.new"
mv "$OUT.new" "$OUT"
echo "done; ids in $OUT"
