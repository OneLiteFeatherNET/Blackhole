#!/usr/bin/env bash
# What is reachable from outside: hosts, the Ingress class, the Services behind it and, above all, the route
# table of the external API host evaluated with the semantics of the Cloudflare tunnel (cloudflared).
# Run: deploy/charts/blackhole/tests/exposure.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
#
# cloudflared semantics (cloudflare-tunnel-ingress-controller v0.0.23 passes Ingress paths through unchanged,
# pkg/controller/transform.go and pkg/cloudflare-controller/transform.go): the rules of a host are sorted by
# path length, longest first; a rule matches when its path, taken as an UNANCHORED regular expression, matches
# the request path; the first match wins; no match falls through to the controller's catch-all 404.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

prod="${WORK_DIR}/prod.yaml"
dev="${WORK_DIR}/dev.yaml"
nginx="${WORK_DIR}/nginx.yaml"
render_to prod-values.yaml "${prod}"
render_to dev-values.yaml "${dev}"
render_to nginx-values.yaml "${nginx}"

# rules_for_host <manifests> <host> <out>  -> lines "<path>\t<service>" of the Ingress rules of that host
rules_for_host() {
  yq eval-all "select(.kind == \"Ingress\") | .spec.rules[] | select(.host == \"$2\") | .http.paths[] | .path + \"\t\" + .backend.service.name" "$1" >"$3"
}

# cf_route <rules file> <request path>  -> the service the tunnel sends it to, or 404
cf_route() {
  local rules="$1" path="$2" len regex service
  while IFS=$'\t' read -r len regex service; do
    if printf '%s\n' "${path}" | grep -Eq -e "${regex}"; then
      echo "${service}"
      return
    fi
  done < <(awk -F'\t' '{ print length($1) "\t" $0 }' "${rules}" | sort -t$'\t' -k1,1nr)
  echo "404"
}

allowed=(
  /punishment /punishment/active /punishment/7/evidence /profile/abc /elo/abc /section /section/all /template
  /report /report/1/feedback /evasion/record /evidence/1 /appeal/1 /staff-link /player/lookup/name
  /player-discord-link /cloudnet/catalogue
)
blocked=(
  / /public /public/session /public/appeal/1 /public/punishment /discord/tickets /discord/leak-check /prometheus
  /swagger/views/swagger-ui /health /health/readiness /auth/me /admin/import /admin/staff-link /dashboard
  /cloudnet /x/punishment /api/profile
)

check_route_table() { # <label> <rules file> <service for allowed routes>
  local label="$1" rules="$2" service="$3" path
  for path in "${allowed[@]}"; do
    assert_eq "${label}: ${path} reaches the backend" "${service}" "$(cf_route "${rules}" "${path}")"
  done
  for path in "${blocked[@]}"; do
    assert_eq "${label}: ${path} is blocked at the edge" "404" "$(cf_route "${rules}" "${path}")"
  done
}

# --- the three hosts ---------------------------------------------------------------------------------------------
api_rules="${WORK_DIR}/api-rules.tsv"
rules_for_host "${prod}" blackhole-api.onelitefeather.net "${api_rules}"
assert_eq "the API host has rules" "1" "$(grep -c . "${api_rules}")"
check_route_table "paths mode" "${api_rules}" t-backend

lengths="$(awk -F'\t' '{ print length($1) }' "${api_rules}" | sort | uniq -d | wc -l)"
assert_eq "no two rules of the API host have the same length" "0" "${lengths}"

assert_eq "the API rule is a regular expression" "ImplementationSpecific" "$(y "${prod}" 'select(.kind == "Ingress" and .metadata.name == "t-backend-api") | .spec.rules[0].http.paths[0].pathType')"
assert_eq "an unanchored plain prefix would have leaked /public/appeal (why the rule is anchored)" "t-backend" \
  "$(printf '/appeal\tt-backend\n' >"${WORK_DIR}/naive.tsv" && cf_route "${WORK_DIR}/naive.tsv" /public/appeal/1)"

panel="${WORK_DIR}/panel.tsv"
rules_for_host "${prod}" blackhole.onelitefeather.net "${panel}"
assert_eq "the panel host routes everything to the panel" "t-web" "$(cf_route "${panel}" /anything)"
portal="${WORK_DIR}/portal.tsv"
rules_for_host "${prod}" appeal.onelitefeather.net "${portal}"
assert_eq "the portal host routes everything to the portal" "t-portal" "$(cf_route "${portal}" /anything)"

hosts="$(y "${prod}" '[select(.kind == "Ingress") | .spec.rules[].host] | sort | join(",")')"
assert_eq "exactly the three decided hosts are exposed" "appeal.onelitefeather.net,blackhole-api.onelitefeather.net,blackhole.onelitefeather.net" "${hosts}"
assert_eq "every Ingress uses the tunnel class" "0" "$(y "${prod}" '[select(.kind == "Ingress") | select(.spec.ingressClassName != "cloudflare-tunnel")] | length')"
assert_eq "every Ingress speaks http to the backend" "0" "$(y "${prod}" '[select(.kind == "Ingress") | select(.metadata.annotations."cloudflare-tunnel-ingress-controller.strrl.dev/backend-protocol" != "http")] | length')"
assert_eq "the backend is not exposed on any host but the API host" "0" "$(y "${prod}" '[select(.kind == "Ingress") | .spec.rules[] | select(.host != "blackhole-api.onelitefeather.net") | .http.paths[] | select(.backend.service.name == "t-backend")] | length')"

# --- every tunnel backend is a normal ClusterIP Service that exists -------------------------------------------------
backends="$(y "${prod}" '[select(.kind == "Ingress") | .spec.rules[].http.paths[].backend.service.name] | unique | .[]')"
for name in ${backends}; do
  assert_eq "${name} exists as a Service" "1" "$(y "${prod}" "[select(.kind == \"Service\" and .metadata.name == \"${name}\")] | length")"
  assert_eq "${name} is not headless" "null" "$(y "${prod}" "select(.kind == \"Service\" and .metadata.name == \"${name}\") | .spec.clusterIP")"
done

# --- the API Ingress exists only when asked for ---------------------------------------------------------------------
off="${WORK_DIR}/api-off.yaml"
render_to prod-values.yaml "${off}" --set backend.externalApi.enabled=false
assert_eq "no API Ingress without externalApi.enabled" "0" "$(y "${off}" '[select(.kind == "Ingress" and .metadata.name == "t-backend-api")] | length')"
assert_eq "the panel Ingress stays" "1" "$(y "${off}" '[select(.kind == "Ingress" and .metadata.name == "t-web")] | length')"

# --- nginx mode ---------------------------------------------------------------------------------------------------------
nginx_rules="${WORK_DIR}/nginx-rules.tsv"
rules_for_host "${nginx}" blackhole-api.onelitefeather.net "${nginx_rules}"
assert_eq "nginx mode: the tunnel routes / to the proxy" $'/\tt-api-proxy' "$(cat "${nginx_rules}")"
conf="$(configmap_value "${nginx}" t-api-proxy nginx.conf)"
locations="${WORK_DIR}/nginx-locations.tsv"
grep -E '^\s*location ~ ' <<<"${conf}" | sed -E 's/^\s*location ~ (.*) \{$/\1/' | awk '{ print $0 "\tt-backend" }' >"${locations}"
assert_eq "nginx mode: one allow-list location" "1" "$(grep -c . "${locations}")"
check_route_table "nginx mode" "${locations}" t-backend
assert_match "nginx answers 404 for everything else" <(echo "${conf}") 'return 404'
assert_no_match "nginx keeps no access log (no IP addresses)" <(echo "${conf}") 'access_log +/'
assert_eq "nginx proxy runs unprivileged" "true" "$(y "${nginx}" 'select(.kind == "Deployment" and .metadata.name == "t-api-proxy") | .spec.template.spec.securityContext.runAsNonRoot')"
assert_eq "no API proxy in paths mode" "0" "$(y "${prod}" '[select(.metadata.name == "t-api-proxy")] | length')"

# --- dev: HTTPRoutes instead of tunnel Ingresses -----------------------------------------------------------------------
assert_eq "dev renders no Ingress" "0" "$(y "${dev}" '[select(.kind == "Ingress")] | length')"
assert_eq "dev renders two HTTPRoutes" "2" "$(y "${dev}" '[select(.kind == "HTTPRoute")] | length')"
route='select(.kind == "HTTPRoute" and .metadata.name == "t-web")'
assert_eq "HTTPRoute parent gateway" "eg" "$(y "${dev}" "${route} | .spec.parentRefs[0].name")"
assert_eq "HTTPRoute parent namespace" "envoy" "$(y "${dev}" "${route} | .spec.parentRefs[0].namespace")"
assert_eq "HTTPRoute listener" "https" "$(y "${dev}" "${route} | .spec.parentRefs[0].sectionName")"
assert_eq "HTTPRoute host" "blackhole-dev.apps.onelite.feather" "$(y "${dev}" "${route} | .spec.hostnames[0]")"
assert_eq "HTTPRoute backend" "t-web" "$(y "${dev}" "${route} | .spec.rules[0].backendRefs[0].name")"
assert_eq "dev has no external API host" "0" "$(y "${dev}" '[select(.kind == "Ingress" and .metadata.name == "t-backend-api")] | length')"
assert_eq "prod renders no HTTPRoute" "0" "$(y "${prod}" '[select(.kind == "HTTPRoute")] | length')"

finish
