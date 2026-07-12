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
- Prompt-engineering audit of agent/skill/rule prompts converged as of 2026-07-12: all four
  `micronaut-*-layer` skills, all three subagents (`.claude/agents/*.md`), all six path-scoped
  rules (`.claude/rules/*.md`), and the hook feedback messages have been reviewed — nothing left
  to improve without a new concrete target (a new skill/agent added since, or an actual observed
  failure from real usage). The recurring cron job that drove this audit (`4c4629dd`) was deleted
  once it converged — don't assume one still exists.

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
