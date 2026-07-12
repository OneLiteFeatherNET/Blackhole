<!--
  Cross-session progress ledger. This is the state that survives a context reset, a
  /compact, or a fresh session picking up where a previous one left off — the
  conversation transcript does not persist, this file does.

  Update it:
  - when you start a non-trivial task: move/add it under "Active"
  - when you finish it: move it to "Recently completed" (keep last ~10, prune older)
  - when you stop mid-task (context limit, session end, blocked): leave enough detail
    under "Active" that a fresh session with no memory of this conversation can resume
    without re-deriving what you already figured out (files touched, why, what's left)

  Keep entries short — this file is for resuming work, not for narrating it. Long-form
  design rationale belongs in commit messages, PR descriptions, or Outline, not here.
-->

# Progress

## Active

_(nothing in flight right now)_

## Backlog

- JUnit 5 coverage: `backend/src/test` doesn't exist yet. See the Ralph-loop backlog at
  `.claude/backlog/backend-test-coverage.md` for the package-by-package breakdown.
- A recurring cron job (`/loop 30m`, job `4c4629dd`, fires every 30 min for up to 7 days) re-runs
  "improve prompts for Prompt Engineering for agents/components". The prompt-engineering audit of
  `.claude/agents/*.md` and `.claude/skills/*/SKILL.md` is done as of 2026-07-12 — the four
  micronaut-*-layer skills were already good, the three custom subagents (backend-reviewer,
  test-runner, liquibase-checker) got a one-example output-format anchor each. If a future firing
  finds no fresh gap (new skill/agent added since, or a concrete complaint from actual usage),
  say so briefly and stop rather than re-auditing unchanged files — and suggest the user
  `CronDelete 4c4629dd` if they don't want further 30-min firings.

## Recently completed

- Prompt-engineering audit of `.claude/agents/*.md`: added a one-example output-format anchor to
  backend-reviewer, test-runner, and liquibase-checker (consistent report formatting across
  invocations); confirmed the four `micronaut-*-layer` skills already meet the bar (trigger-clause
  descriptions, before/after examples, review checklists, real-codebase evidence) — no changes.
- Split CLAUDE.md's per-package "Backend architecture" detail (controller versioning,
  events/connector, elo, appeal, evasion, database) into path-scoped `.claude/rules/*.md` files —
  CLAUDE.md dropped from 204 to 170 lines, detail now loads only when touching that package.
- Added `.claude/agents/liquibase-checker.md` (read-only: native-changeType usage, append-only
  placement, flags checks Liquibase XML can't express that need app-layer enforcement).
- Added `.claude/settings.json` hooks: block `git push --force`/`--no-verify`, block hand-edits
  to generated OpenAPI client sources, block edits to already-applied Liquibase `<changeSet>`
  entries, require a passing `./gradlew :backend:test` before committing staged
  `backend/src/main/java` changes, re-inject `.claude/PROGRESS.md` after `/compact`.
- Added `.claude/agents/backend-reviewer.md` (read-only layering review) and
  `.claude/agents/test-runner.md` (runs `:backend:test`, reports results).
