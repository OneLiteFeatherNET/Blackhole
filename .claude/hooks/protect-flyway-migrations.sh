#!/bin/bash
# Flyway migrations are append-only in this repo (CLAUDE.md, database/ section): never edit an
# already-applied V<n>__*.sql file, add a new migration with the next version instead.
# A migration counts as applied once it is tracked by git (index/HEAD) or exists on origin/main.
# Creating a new, untracked migration file is allowed.
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

case "$FILE_PATH" in
  */db/migration/V*__*.sql) ;;
  *) exit 0 ;;
esac

DIR=$(dirname "$FILE_PATH")
[ -d "$DIR" ] || exit 0
ROOT=$(git -C "$DIR" rev-parse --show-toplevel 2>/dev/null) || exit 0
REL=${FILE_PATH#"$ROOT"/}

if git -C "$ROOT" ls-files --error-unmatch -- "$REL" >/dev/null 2>&1 \
  || git -C "$ROOT" cat-file -e "origin/main:$REL" 2>/dev/null; then
  echo "Blocked: $REL is an already-committed Flyway migration. Migrations are append-only here - add a NEW db/migration/V<next>__<description>.sql instead of editing an applied one." >&2
  exit 2
fi
exit 0
