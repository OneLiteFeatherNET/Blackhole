#!/usr/bin/env bash
# The RFC 9116 security.txt wiring: the securityTxt values render as BLACKHOLE_SECURITY_* in the portal and web
# Deployments, and the render fails on a contact without an expiry, a malformed expiry, or a panel without its origin.
# Run: deploy/charts/blackhole/tests/security-txt.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
# The time window (past, more than one year ahead) is checked by the apps at startup, not here: this test never
# reads the clock.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

prod="${WORK_DIR}/prod.yaml"
render_to prod-values.yaml "${prod}"

# The chart default contact is the organisation mailbox, and it is the only default in the block.
assert_eq "the default contact is the organisation mailbox" "mailto:security@onelitefeather.net" \
  "$(helm show values "${CHART_DIR}" | yq eval '.securityTxt.contact' -)"
assert_eq "the default expiry is empty, so every deployment sets it" "" \
  "$(helm show values "${CHART_DIR}" | yq eval '.securityTxt.expires' -)"

# (a) a contact without an expiry fails the render and names the expiry value
expect_render_failure "a contact without an expiry fails the render" "securityTxt.expires" prod-values.yaml \
  --set securityTxt.expires=

# (d) a malformed expiry fails the render and names the expiry value
expect_render_failure "a malformed expiry fails the render" "securityTxt.expires" prod-values.yaml \
  --set "securityTxt.expires=next year"

# (d') an expiry without a time zone is not an RFC 3339 timestamp
expect_render_failure "an expiry without a time zone fails the render" "securityTxt.expires" prod-values.yaml \
  --set securityTxt.expires=2027-06-30T12:00:00

# (b) a contact and a valid expiry render into the portal and the web Deployment
assert_contains "the portal Deployment carries the contact" "${prod}" "mailto:security@example.invalid"
assert_eq "the portal Deployment carries BLACKHOLE_SECURITY_CONTACT" "mailto:security@example.invalid" \
  "$(pod_env "${prod}" t-portal BLACKHOLE_SECURITY_CONTACT | yq eval '.value' -)"
assert_eq "the portal Deployment carries BLACKHOLE_SECURITY_EXPIRES" "2027-06-30T12:00:00Z" \
  "$(pod_env "${prod}" t-portal BLACKHOLE_SECURITY_EXPIRES | yq eval '.value' -)"
assert_eq "the web Deployment carries BLACKHOLE_SECURITY_CONTACT" "mailto:security@example.invalid" \
  "$(pod_env "${prod}" t-web BLACKHOLE_SECURITY_CONTACT | yq eval '.value' -)"
assert_eq "the web Deployment carries BLACKHOLE_SECURITY_EXPIRES" "2027-06-30T12:00:00Z" \
  "$(pod_env "${prod}" t-web BLACKHOLE_SECURITY_EXPIRES | yq eval '.value' -)"
assert_eq "the web Deployment carries its own origin as BLACKHOLE_UI_ORIGIN" "https://blackhole.onelitefeather.net" \
  "$(pod_env "${prod}" t-web BLACKHOLE_UI_ORIGIN | yq eval '.value' -)"

# (c) clearing the contact renders no BLACKHOLE_SECURITY_CONTACT, with or without an expiry
cleared="${WORK_DIR}/cleared.yaml"
render_to prod-values.yaml "${cleared}" --set securityTxt.contact= --set securityTxt.expires=
assert_not_contains "clearing the contact renders no BLACKHOLE_SECURITY_CONTACT" "${cleared}" "BLACKHOLE_SECURITY_CONTACT"

# (e) a contact needs the panel's origin: security.txt names its Canonical URL on it
expect_render_failure "a contact without the panel origin fails the render" "web.origin" prod-values.yaml \
  --set web.origin=

finish
