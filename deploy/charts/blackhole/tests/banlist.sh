#!/usr/bin/env bash
# The public ban list: off by default, and when on, the client names, the hash files and the public key reach
# the backend only through configuration and Secret references, never as literal values.
# Run: deploy/charts/blackhole/tests/banlist.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
# The release name is "t": the backend is t-backend.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

backend='select(.kind == "Deployment" and .metadata.name == "t-backend") | .spec.template.spec'
clients='{website,partner-a}'
enabled=(--set "banList.clients=${clients}" --set banList.existingSecret=blackhole-ban-list
  --set publicBanKey.existingSecret=blackhole-public-ban)

# --- off by default: nothing of the feature is rendered ----------------------------------------------------------
off="${WORK_DIR}/off.yaml"
render_to prod-values.yaml "${off}"
assert_eq "no ban list setting in the default render" "0" "$(grep -c 'BLACKHOLE_BAN_LIST' "${off}" || true)"
assert_eq "no public ban key in the default render" "0" "$(grep -c 'BLACKHOLE_PUBLIC_BAN_KEY' "${off}" || true)"
assert_eq "no ban list volume in the default render" "0" \
  "$(y "${off}" "${backend} | [.volumes[] | select(.name == \"ban-list\")] | length")"
assert_eq "the default ban list has no clients" "0" "$(yq '.banList.clients | length' "${CHART_DIR}/values.yaml")"

# --- the public key alone: a Secret reference, no client files -----------------------------------------------------
keyonly="${WORK_DIR}/keyonly.yaml"
render_to prod-values.yaml "${keyonly}" --set publicBanKey.existingSecret=blackhole-public-ban
key_entry="$(pod_env "${keyonly}" t-backend BLACKHOLE_PUBLIC_BAN_KEY)"
assert_eq "the public ban key comes from its Secret" "blackhole-public-ban" \
  "$(yq eval '.valueFrom.secretKeyRef.name' - <<<"${key_entry}")"
assert_eq "the public ban key reads the fixed key of that Secret" "BLACKHOLE_PUBLIC_BAN_KEY" \
  "$(yq eval '.valueFrom.secretKeyRef.key' - <<<"${key_entry}")"
assert_eq "the public ban key has no literal value" "null" "$(yq eval '.value' - <<<"${key_entry}")"
assert_eq "a public key without clients mounts no ban list volume" "0" \
  "$(y "${keyonly}" "${backend} | [.volumes[] | select(.name == \"ban-list\")] | length")"

# --- enabled: client names, hash files and the key ------------------------------------------------------------------
on="${WORK_DIR}/on.yaml"
render_to prod-values.yaml "${on}" "${enabled[@]}"
assert_eq "the client names render into the backend settings" "website,partner-a" \
  "$(configmap_value "${on}" t-backend BLACKHOLE_BAN_LIST_CLIENTS)"
assert_eq "the backend reads the hash files from the mount directory" "/etc/blackhole/ban-list" \
  "$(configmap_value "${on}" t-backend BLACKHOLE_BAN_LIST_SECRETS_DIR)"
assert_eq "the ban list volume comes from the ban list Secret" "blackhole-ban-list" \
  "$(y "${on}" "${backend} | .volumes[] | select(.name == \"ban-list\") | .secret.secretName")"
assert_eq "the volume maps one key per client, each as a file named after it" "website:website,partner-a:partner-a" \
  "$(y "${on}" "${backend} | .volumes[] | select(.name == \"ban-list\") | [.secret.items[] | .key + \":\" + .path] | join(\",\")")"
assert_eq "the hash files are mounted at the directory the backend reads" "/etc/blackhole/ban-list" \
  "$(y "${on}" "${backend} | .containers[0].volumeMounts[] | select(.name == \"ban-list\") | .mountPath")"
assert_eq "the hash files are mounted read-only" "true" \
  "$(y "${on}" "${backend} | .containers[0].volumeMounts[] | select(.name == \"ban-list\") | .readOnly")"
key_on="$(pod_env "${on}" t-backend BLACKHOLE_PUBLIC_BAN_KEY)"
assert_eq "the enabled public ban key still comes from its Secret" "blackhole-public-ban" \
  "$(yq eval '.valueFrom.secretKeyRef.name' - <<<"${key_on}")"

# --- no password or hash in the values or the render ----------------------------------------------------------------
assert_eq "no Argon2 hash in the chart values" "0" "$(grep -c 'argon2' "${CHART_DIR}/values.yaml" || true)"
assert_eq "no Argon2 hash in the enabled render" "0" "$(grep -c 'argon2' "${on}" || true)"
assert_eq "no literal public key in the enabled render" "0" \
  "$(y "${on}" '[select(.kind == "Deployment") | .spec.template.spec.containers[].env[]? | select(.name == "BLACKHOLE_PUBLIC_BAN_KEY") | select(.value != null)] | length')"
assert_eq "the chart renders no Secret object with the feature on" "0" "$(y "${on}" '[select(.kind == "Secret")] | length')"

# --- the rules of the values ------------------------------------------------------------------------------------------
expect_render_failure "clients need their Secret" "banList.existingSecret is required" prod-values.yaml \
  --set "banList.clients=${clients}" --set publicBanKey.existingSecret=blackhole-public-ban
expect_render_failure "clients need the public ban key" "publicBanKey.existingSecret is required" prod-values.yaml \
  --set "banList.clients=${clients}" --set banList.existingSecret=blackhole-ban-list
expect_render_failure "a client name with capitals is refused" "/banList/clients" prod-values.yaml \
  --set 'banList.clients={Website}' --set banList.existingSecret=blackhole-ban-list \
  --set publicBanKey.existingSecret=blackhole-public-ban
expect_render_failure "a duplicate client name is refused" "/banList/clients" prod-values.yaml \
  --set 'banList.clients={website,website}' --set banList.existingSecret=blackhole-ban-list \
  --set publicBanKey.existingSecret=blackhole-public-ban
expect_render_failure "a plaintext public key is refused" "/publicBanKey" prod-values.yaml \
  --set publicBanKey.value=not-a-secret
expect_render_failure "a plaintext client hash is refused" "/banList" prod-values.yaml \
  --set 'banList.clientHashes.website=$argon2id$v=19$m=19456,t=2,p=1$x$y'

# --- the external host routes /public/bans only with clients, and nothing else under /public -------------------------
# The tunnel matches the one rule of the API host as an unanchored regular expression (see exposure.sh).
api_path_regex() { # <manifests> -> the path regex of the API host rule
  y "$1" 'select(.kind == "Ingress" and .metadata.name == "t-backend-api") | .spec.rules[0].http.paths[0].path'
}
routed() { # <regex> <path> -> pass when the tunnel would send the path to the backend
  if printf '%s\n' "$2" | grep -Eq -e "$1"; then return 0; else return 1; fi
}
assert_routed() { # <message> <manifests> <path>
  if routed "$(api_path_regex "$2")" "$3"; then pass; else fail "$1 ($3 is not routed)"; fi
}
assert_refused() { # <message> <manifests> <path>
  if routed "$(api_path_regex "$2")" "$3"; then fail "$1 ($3 is routed)"; else pass; fi
}

routed_on="${WORK_DIR}/routed-on.yaml"
render_to prod-values.yaml "${routed_on}" "${enabled[@]}"
assert_routed "clients set: the list is routed" "${routed_on}" /public/bans
assert_routed "clients set: one entry is routed" "${routed_on}" /public/bans/AbCdEfGhIjKlMnOpQrStUv
assert_routed "clients set: the summary is routed" "${routed_on}" /public/bans/summary
assert_refused "clients set: /public/bansx is not routed" "${routed_on}" /public/bansx
assert_refused "clients set: /public/session stays refused" "${routed_on}" /public/session
assert_refused "clients set: /public/appeal stays refused" "${routed_on}" /public/appeal/1
assert_refused "clients set: /public itself stays refused" "${routed_on}" /public
assert_refused "clients set: /public/punishment stays refused" "${routed_on}" /public/punishment
assert_refused "clients set: /discord stays refused" "${routed_on}" /discord/tickets
assert_refused "clients set: /admin stays refused" "${routed_on}" /admin/import
assert_eq "clients set: the nginx proxy routes the list too" "1" \
  "$(grep -c 'public/bans' <(render prod-values.yaml "${enabled[@]}" --set backend.externalApi.mode=nginx) || true)"

routed_off="${WORK_DIR}/routed-off.yaml"
render_to prod-values.yaml "${routed_off}" --set publicBanKey.existingSecret=blackhole-public-ban
assert_refused "clients empty: /public/bans is refused" "${routed_off}" /public/bans
assert_refused "clients empty: /public/bans/summary is refused" "${routed_off}" /public/bans/summary
assert_refused "clients empty: /public itself stays refused" "${routed_off}" /public
assert_eq "clients empty: the nginx proxy has no list route" "0" \
  "$(grep -c 'public/bans' <(render prod-values.yaml --set backend.externalApi.mode=nginx) || true)"

finish
