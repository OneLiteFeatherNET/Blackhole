#!/usr/bin/env bash
# The analytics refresh interval reaches the backend as plain configuration, and the chart creates no Secret for the
# Metabase connection (that Secret is made in Kubernetes-FLUX).
# Run: deploy/charts/blackhole/tests/analytics.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
# The release name is "t": the backend's ConfigMap is t-backend.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# --- the default: 15 minutes, as configuration of the backend -------------------------------------------------------
default="${WORK_DIR}/default.yaml"
render_to prod-values.yaml "${default}"
assert_eq "the default refresh interval is 15 minutes" "15m" \
  "$(configmap_value "${default}" t-backend BLACKHOLE_ANALYTICS_REFRESH_INTERVAL)"

# --- a value from analytics.refreshInterval -------------------------------------------------------------------------
custom="${WORK_DIR}/custom.yaml"
render_to prod-values.yaml "${custom}" --set analytics.refreshInterval=5m
assert_eq "the refresh interval comes from analytics.refreshInterval" "5m" \
  "$(configmap_value "${custom}" t-backend BLACKHOLE_ANALYTICS_REFRESH_INTERVAL)"

# --- the Metabase connection Secret is never rendered by the chart --------------------------------------------------
assert_eq "the chart renders no Metabase connection Secret" "0" \
  "$(grep -c 'blackhole-analytics-metabase' "${custom}" || true)"

finish
