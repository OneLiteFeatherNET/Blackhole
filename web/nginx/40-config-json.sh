#!/bin/sh
# Writes the runtime configuration the UI loads from /config.json when the container starts:
#   BLACKHOLE_UI_SOURCE    the operator UUID sent as source / resolvedBy on every write (required)
#   BLACKHOLE_UI_REVIEWER  the UUID sent as reviewerId when deciding appeals (default: the source)
#   BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES, BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES  (optional, bytes)
#   BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES  (optional, comma separated MIME types)
#                          pre-check limits of the evidence upload; the UI falls back to the backend defaults
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

evidence=""
add_evidence() {
  evidence="${evidence:+$evidence,}$1"
}
for name in MAX_IMAGE_BYTES MAX_VIDEO_BYTES; do
  eval "value=\"\${BLACKHOLE_UI_EVIDENCE_$name:-}\""
  [ -z "$value" ] && continue
  if ! printf '%s' "$value" | grep -Eq '^[1-9][0-9]{0,15}$'; then
    echo "40-config-json.sh: BLACKHOLE_UI_EVIDENCE_$name must be a positive number of bytes (got: '$value')" >&2
    exit 1
  fi
  key="maxImageBytes"
  [ "$name" = MAX_VIDEO_BYTES ] && key="maxVideoBytes"
  add_evidence "\"$key\":$value"
done
types="${BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES:-}"
if [ -n "$types" ]; then
  if ! printf '%s' "$types" | grep -Eq '^[a-z0-9.+-]+/[a-z0-9.+-]+(,[a-z0-9.+-]+/[a-z0-9.+-]+)*$'; then
    echo "40-config-json.sh: BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES must be comma separated MIME types, e.g. image/png,video/mp4 (got: '$types')" >&2
    exit 1
  fi
  add_evidence "\"allowedTypes\":[\"$(printf '%s' "$types" | sed 's/,/","/g')\"]"
fi

mkdir -p "$CONFIG_DIR"
# Every value passed a strict pattern above, so none can break out of the JSON strings.
printf '{"source":"%s","reviewer":"%s"' "$source_id" "$reviewer_id" > "$CONFIG_DIR/config.json"
[ -n "$evidence" ] && printf ',"evidence":{%s}' "$evidence" >> "$CONFIG_DIR/config.json"
printf '}\n' >> "$CONFIG_DIR/config.json"
echo "40-config-json.sh: wrote $CONFIG_DIR/config.json"
