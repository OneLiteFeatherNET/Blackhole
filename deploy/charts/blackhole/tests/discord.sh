#!/usr/bin/env bash
# The discord bot's settings: the feature flag DISCORD_STAFF_ACTIONS reaches the bot through its config map.
# Run: deploy/charts/blackhole/tests/discord.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
# The release name is "t": the bot is t-discord and reads its settings from the ConfigMap t-discord.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

prod="${WORK_DIR}/prod.yaml"
render_to prod-values.yaml "${prod}"

# --- the flag is unset by default: the bot's own default (off) applies --------------------------------------
assert_eq "DISCORD_STAFF_ACTIONS is not rendered by default" "null" "$(configmap_value "${prod}" t-discord DISCORD_STAFF_ACTIONS)"

# --- set through discord.config: the discord Deployment gets it from its config map ------------------------
on="${WORK_DIR}/on.yaml"
render_to prod-values.yaml "${on}" --set discord.config.DISCORD_STAFF_ACTIONS=true
assert_eq "DISCORD_STAFF_ACTIONS=true reaches the discord ConfigMap" "true" "$(configmap_value "${on}" t-discord DISCORD_STAFF_ACTIONS)"
assert_eq "the discord Deployment loads the ConfigMap that holds the flag" "t-discord" \
  "$(y "${on}" 'select(.kind == "Deployment" and .metadata.name == "t-discord") | .spec.template.spec.containers[0].envFrom[] | select(.configMapRef) | .configMapRef.name')"
assert_eq "the flag is not a secret: it is not in a Secret reference" "0" \
  "$(y "${on}" '[select(.kind == "Deployment" and .metadata.name == "t-discord") | .spec.template.spec.containers[0].env[]? | select(.name == "DISCORD_STAFF_ACTIONS")] | length')"

off="${WORK_DIR}/off.yaml"
render_to prod-values.yaml "${off}" --set discord.config.DISCORD_STAFF_ACTIONS=false
assert_eq "DISCORD_STAFF_ACTIONS=false is rendered as false" "false" "$(configmap_value "${off}" t-discord DISCORD_STAFF_ACTIONS)"

finish
