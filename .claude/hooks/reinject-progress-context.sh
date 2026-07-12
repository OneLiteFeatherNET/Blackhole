#!/bin/bash
# Re-injects the progress ledger and recent history after /compact, since compaction
# summarizes the conversation but this file is the actual durable state.
cd "$CLAUDE_PROJECT_DIR" || exit 0

echo "--- .claude/PROGRESS.md (update this as you work, don't just read it) ---"
cat .claude/PROGRESS.md 2>/dev/null
echo "--- recent commits ---"
git log --oneline -5 2>/dev/null
echo "--- working tree status ---"
git status --short 2>/dev/null
exit 0
