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

- OpenSpec changes, implemented by Haiku agents on separate branches (based on `main`; merge
  this planning branch in before PRs so `openspec/` is included):
  - `feat/postgresql` (switch-to-postgresql): changelog rebuilt as PostgreSQL-only
    `db/changelog/changelog.xml` (user decision), schema diff-verified (since replaced by Flyway).
    Open: 1.1/1.2/2.2/3.x need a Gradle build + app start (unverified).
  - `feat/dragonfly-cache`: infra/docs/proxy committed; backend work parked as
    `WIP-dragonfly-backend.patch` (apply, build, test, then delete the patch). Branch does not
    compile until the patch is applied.
  - `feat/admin-web-ui`: scaffold + MD3 tokens/components + shell done (npm build/test green);
    tasks 2.3, 4.2-4.7, 5.x pending (need core API); tick 2.1/2.2/3.1/3.2/4.1 in tasks.md.
  - `staff-temp-bans-elo-escalation`: not started.
- BLOCKER: Java builds need private Maven repo credentials
  (`ORG_GRADLE_PROJECT_OneLiteFeatherRepositoryUsername` / `...Password` env vars). JDK 25 lives
  at /root/jdk (set via ~/.gradle/gradle.properties); local PG/Redis/RabbitMQ were started by hand.

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

- `add-appeal-portal` group 8 (PR `docs(appeal): document the appeal portal`): `docs/appeal-portal.md`
  (operators: portal container, session limits, Ban ID key and backfill gauge, edge rules, no body logging) and
  `docs/appeal-portal-staff.md` (Ban ID, leak, reissue, conversation, export, protocol, retention pending legal).
  Gap found: `BanIdBackfill --reissue-all` is ticked in 1.3 but not in the code; documented as a known gap.
  Task 6.5 is its own PR (`feat(web)`).
- `add-discord-appeal-tickets` group 6 (PR `docs(discord): document and deploy the discord bot`): compose
  service `discord` behind the profile of the same name, `docs/discord-bot.md` (portal, permissions,
  edge rules 30/120 per min with 429, archive deletion, health), README additions. The bot image CI job
  and smoke check already existed (verified, unchanged). Group 5 (panel) is its own PR. Still open: 7.x
  needs a real test guild.
- Prompt-engineering audit of `.claude/agents/*.md`: added a one-example output-format anchor to
  backend-reviewer, test-runner, and migration-checker (consistent report formatting across
  invocations); confirmed the four `micronaut-*-layer` skills already meet the bar (trigger-clause
  descriptions, before/after examples, review checklists, real-codebase evidence) — no changes.
- Split CLAUDE.md's per-package "Backend architecture" detail (controller versioning,
  events/connector, elo, appeal, evasion, database) into path-scoped `.claude/rules/*.md` files —
  CLAUDE.md dropped from 204 to 170 lines, detail now loads only when touching that package.
- Added `.claude/agents/migration-checker.md` (read-only: native-changeType usage, append-only
  placement, flags checks SQL migrations do not cover that need app-layer enforcement).
- Added `.claude/settings.json` hooks: block `git push --force`/`--no-verify`, block hand-edits
  to generated OpenAPI client sources, block edits to already-applied Flyway migrations
  entries, require a passing `./gradlew :backend:test` before committing staged
  `backend/src/main/java` changes, re-inject `.claude/PROGRESS.md` after `/compact`.
- Added `.claude/agents/backend-reviewer.md` (read-only layering review) and
  `.claude/agents/test-runner.md` (runs `:backend:test`, reports results).
