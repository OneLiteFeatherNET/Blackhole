#!/usr/bin/env bash
# The firewall pull feeds: off by default, and when clients are set, the client names, the hash files (read-only, one
# per client) and the optional source allow-list reach the backend only through configuration and Secret references.
# Run: deploy/charts/blackhole/tests/firewall-feed.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
# The release name is "t": the backend is t-backend.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

backend='select(.kind == "Deployment" and .metadata.name == "t-backend") | .spec.template.spec'
clients='{office-fw,partner-b}'
enabled=(--set "firewallFeed.clients=${clients}" --set firewallFeed.existingSecret=blackhole-firewall-feed
  --set 'firewallFeed.allowedSources=203.0.113.10\,198.51.100.0/24')

# --- off by default: nothing of the feature is rendered ----------------------------------------------------------
off="${WORK_DIR}/off.yaml"
render_to prod-values.yaml "${off}"
assert_eq "no firewall feed setting in the default render" "0" \
  "$(grep -c 'BLACKHOLE_FIREWALL_FEED' "${off}" || true)"
assert_eq "no firewall feed volume in the default render" "0" \
  "$(y "${off}" "${backend} | [.volumes[] | select(.name == \"firewall-feed\")] | length")"
assert_eq "the default firewall feed has no clients" "0" \
  "$(yq '.firewallFeed.clients | length' "${CHART_DIR}/values.yaml")"

# --- enabled: client names, hash files and the allow-list ------------------------------------------------------------
on="${WORK_DIR}/on.yaml"
render_to prod-values.yaml "${on}" "${enabled[@]}"
assert_eq "the client names render into the backend settings" "office-fw,partner-b" \
  "$(configmap_value "${on}" t-backend BLACKHOLE_FIREWALL_FEED_CLIENTS)"
assert_eq "the backend reads the hash files from the mount directory" "/etc/blackhole/firewall-feed" \
  "$(configmap_value "${on}" t-backend BLACKHOLE_FIREWALL_FEED_SECRETS_DIR)"
assert_eq "the allow-list reaches the backend as configured" "203.0.113.10,198.51.100.0/24" \
  "$(configmap_value "${on}" t-backend BLACKHOLE_FIREWALL_FEED_ALLOWED_SOURCES)"
assert_eq "the firewall feed volume comes from the feed Secret" "blackhole-firewall-feed" \
  "$(y "${on}" "${backend} | .volumes[] | select(.name == \"firewall-feed\") | .secret.secretName")"
assert_eq "the hash files are mounted at the directory the backend reads" "/etc/blackhole/firewall-feed" \
  "$(y "${on}" "${backend} | .containers[0].volumeMounts[] | select(.name == \"firewall-feed\") | .mountPath")"
assert_eq "the hash files are mounted read-only" "true" \
  "$(y "${on}" "${backend} | .containers[0].volumeMounts[] | select(.name == \"firewall-feed\") | .readOnly")"
assert_eq "the chart renders no Secret object with the feed on" "0" \
  "$(y "${on}" '[select(.kind == "Secret")] | length')"

assert_eq "the volume also maps the rotation key of each client, optionally" \
  "office-fw:office-fw,office-fw.next:office-fw.next,partner-b:partner-b,partner-b.next:partner-b.next" \
  "$(y "${on}" "${backend} | .volumes[] | select(.name == \"firewall-feed\") | [.secret.items[] | .key + \":\" + .path] | join(\",\")")"
assert_eq "the feed volume tolerates a missing rotation key" "true" \
  "$(y "${on}" "${backend} | .volumes[] | select(.name == \"firewall-feed\") | .secret.optional")"

# --- the external API host routes /firewall/feed only with clients ---------------------------------------------------
# The same anchored expression the tunnel and the nginx proxy use (see banlist.sh and exposure.sh).
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
feed_path="/firewall/feed/TOKEN-PLACEHOLDER/ips"
assert_routed "clients set: the firewall feed is routed" "${on}" "${feed_path}"
assert_routed "clients set: the networks feed is routed" "${on}" "/firewall/feed/TOKEN-PLACEHOLDER/networks"
assert_refused "clients set: /firewall/feedx is not routed" "${on}" "/firewall/feedx/TOKEN-PLACEHOLDER/ips"
assert_refused "clients set: /firewall itself stays refused" "${on}" "/firewall"
assert_refused "clients set: /firewall/admin stays refused" "${on}" "/firewall/admin/ips"
assert_eq "clients set: the nginx proxy routes the feed too" "1" \
  "$(grep -c 'firewall/feed' <(render prod-values.yaml "${enabled[@]}" --set backend.externalApi.mode=nginx) || true)"
routed_off="${WORK_DIR}/routed-off.yaml"
render_to prod-values.yaml "${routed_off}"
assert_refused "clients empty: the firewall feed is refused" "${routed_off}" "${feed_path}"
assert_eq "clients empty: the nginx proxy has no feed route" "0" \
  "$(grep -c 'firewall/feed' <(render prod-values.yaml --set backend.externalApi.mode=nginx) || true)"

# --- without an allow-list: the setting is empty, which admits every peer with a valid token -----------------------
open="${WORK_DIR}/open.yaml"
render_to prod-values.yaml "${open}" --set 'firewallFeed.clients={office-fw}' --set firewallFeed.existingSecret=blackhole-firewall-feed
assert_eq "an empty allow-list renders as an empty setting" "" \
  "$(configmap_value "${open}" t-backend BLACKHOLE_FIREWALL_FEED_ALLOWED_SOURCES)"

# --- the rules of the values ------------------------------------------------------------------------------------------
expect_render_failure "clients need their Secret" "firewallFeed.existingSecret is required" prod-values.yaml \
  --set "firewallFeed.clients=${clients}"
expect_render_failure "a client name with capitals is refused" "/firewallFeed/clients" prod-values.yaml \
  --set 'firewallFeed.clients={Office}' --set firewallFeed.existingSecret=blackhole-firewall-feed
expect_render_failure "a duplicate client name is refused" "/firewallFeed/clients" prod-values.yaml \
  --set 'firewallFeed.clients={office-fw,office-fw}' --set firewallFeed.existingSecret=blackhole-firewall-feed
expect_render_failure "an allow-list host name is refused" "/firewallFeed/allowedSources" prod-values.yaml \
  "${enabled[@]}" --set 'firewallFeed.allowedSources=firewall.example.net'
expect_render_failure "an allow-list with a space is refused" "/firewallFeed/allowedSources" prod-values.yaml \
  "${enabled[@]}" --set 'firewallFeed.allowedSources=203.0.113.10 evil'
expect_render_failure "a plaintext token value is refused" "/firewallFeed" prod-values.yaml \
  --set firewallFeed.token=not-a-hash

finish
