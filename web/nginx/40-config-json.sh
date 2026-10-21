#!/bin/sh
# Writes the runtime configuration the UI loads from /config.json when the container starts:
#   BLACKHOLE_UI_SOURCE    the operator UUID sent as source / resolvedBy on every write (required)
#   BLACKHOLE_UI_REVIEWER  the UUID sent as reviewerId when deciding appeals (default: the source)
# CONFIG_DIR is only overridden by the tests.
set -eu

CONFIG_DIR="${CONFIG_DIR:-/tmp/config}"
UUID_PATTERN='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

source_id="${BLACKHOLE_UI_SOURCE:-}"
reviewer_id="${BLACKHOLE_UI_REVIEWER:-$source_id}"

for name in BLACKHOLE_UI_SOURCE BLACKHOLE_UI_REVIEWER; do
  if [ "$name" = BLACKHOLE_UI_SOURCE ]; then value="$source_id"; else value="$reviewer_id"; fi
  if ! printf '%s' "$value" | grep -Eq "$UUID_PATTERN"; then
    echo "40-config-json.sh: $name must be a UUID, e.g. 3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b (got: '$value')" >&2
    exit 1
  fi
done

mkdir -p "$CONFIG_DIR"
# Both values passed the UUID check, so they cannot break out of the JSON strings.
printf '{"source":"%s","reviewer":"%s"}\n' "$source_id" "$reviewer_id" > "$CONFIG_DIR/config.json"
echo "40-config-json.sh: wrote $CONFIG_DIR/config.json"
