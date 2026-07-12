#!/bin/bash
# Blocks git push --force/-f (without --force-with-lease) and any --no-verify,
# per CLAUDE.md's "no force-push, no skipping hooks without explicit user request".
INPUT=$(cat)
CMD=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

# Only scan the actual command text, not heredoc bodies (e.g. a commit message
# passed via `git commit -m "$(cat <<'EOF' ... EOF)"` that merely *mentions*
# --force/--no-verify as prose would otherwise false-positive on itself).
CMD_HEAD=${CMD%%<<*}

REASON=""
if printf '%s' "$CMD_HEAD" | grep -q 'push' \
  && printf '%s' "$CMD_HEAD" | grep -Eq -- '(^|[[:space:]])(-f|--force)([[:space:]]|$)' \
  && ! printf '%s' "$CMD_HEAD" | grep -q -- '--force-with-lease'; then
  REASON="Blocked: git push --force/-f is not allowed without explicit user confirmation (CLAUDE.md). Ask the user first, or use --force-with-lease if they've approved it."
elif printf '%s' "$CMD_HEAD" | grep -q -- '--no-verify'; then
  REASON="Blocked: git --no-verify would skip hooks and is not allowed without explicit user confirmation (CLAUDE.md). Ask the user first."
fi

if [ -n "$REASON" ]; then
  echo "$REASON" >&2
  exit 2
fi
exit 0
