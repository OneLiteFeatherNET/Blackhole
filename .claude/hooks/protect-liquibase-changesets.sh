#!/bin/bash
# Liquibase changelogs are append-only in this repo (CLAUDE.md, database/ section):
# never edit an already-applied <changeSet>, always append a new one.
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
OLD_STRING=$(echo "$INPUT" | jq -r '.tool_input.old_string // empty')

if [[ "$FILE_PATH" == *db.changelog-master.xml ]]; then
  if [ "$TOOL_NAME" = "Write" ]; then
    echo "Blocked: don't Write (full overwrite) db.changelog-master.xml — use Edit to append a new <changeSet>, preserving all existing ones." >&2
    exit 2
  fi
  if printf '%s' "$OLD_STRING" | grep -q '<changeSet'; then
    echo "Blocked: this edit modifies/removes an existing <changeSet> in db.changelog-master.xml. Changelogs are append-only here — add a NEW <changeSet> instead (e.g. inserted just before </databaseChangeLog>)." >&2
    exit 2
  fi
fi
exit 0
