# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Blackhole is a ban system for a single Minecraft network, built to be GDPR-compliant by design.
One backend deployment serves one network's staff, players, punishment history and integrations,
while keeping personally identifiable data (IPs, raw player UUIDs) hashed or tokenized wherever
it's stored or published. There is no multi-tenancy - if multiple networks are ever needed, that's
handled by running separate deployments, not by sharing one deployment across them.

Punishment decisions are driven by a **dual-ELO system**: every player carries a separate
`chatElo` and `gameplayElo` score. Reports and a pluggable chat-toxicity scorer feed deltas into
these scores, and crossing a configurable soft/hard threshold automatically applies a temporary
or permanent punishment — without requiring a moderator to be online. A structured appeal
workflow lets players contest both automatic and manual punishments.

## Modules

Gradle multi-module project (Kotlin DSL), Java 21+ (backend toolchain targets 25):

| Module     | What it is                                                                                    |
|------------|------------------------------------------------------------------------------------------------|
| `backend`  | The Micronaut HTTP API — punishments, reports, ELO, appeals, dashboard.                       |
| `velocity` | A Velocity proxy plugin that enforces punishments and feeds chat/session data to the backend.  |
| `client`   | A Java API client generated from `client/specs/blackhole-api-*.yml` via OpenAPI Generator.     |
| `phoca`    | Shared utility library (metadata/expiry/duration abstractions) used across modules.             |

`client` has no hand-written API code — `compileJava` depends on `openApiGenerate`, which
regenerates `net.onelitefeather.blackhole.client.{api,invoker,model}` from the pinned spec file
in `client/build.gradle.kts` (`inputSpec`). Bump the spec filename there when publishing a new
API version, don't edit generated sources directly.

## Commands

```shell
# Build everything (all modules)
./gradlew clean build

# Run just the backend against local infra (needs docker/docker-compose.yml running and the
# Entra dev env loaded, see .claude/rules/security.md)
cd docker && docker compose up -d && cd ..
set -a; . ~/.config/blackhole/entra-dev.env; set +a
./gradlew :backend:run

# Run a single module's tests
./gradlew :backend:test

# Run a single test class/method (JUnit 5 filter)
./gradlew :backend:test --tests "net.onelitefeather.blackhole.backend.SomeTest"
./gradlew :backend:test --tests "net.onelitefeather.blackhole.backend.SomeTest.someMethod"

# Regenerate the OpenAPI client sources explicitly
./gradlew :client:openApiGenerate

# Run the Velocity plugin against a local Velocity instance
./gradlew :velocity:runVelocity

# Helm chart tests (needs helm v4.3.0 and yq v4.53.6; runbook: docs/kubernetes.md)
deploy/charts/blackhole/tests/run-all.sh
```

Test coverage is minimal — only `backend/src/test/java/.../playerresolver/service/PlayerResolverServiceTest.java`
exists so far, no other module has a `src/test` yet. JUnit 5 is wired up via the Micronaut/root
`build.gradle.kts` (`useJUnitPlatform()`, `jacocoTestReport` runs after `test`), ready for more.
Local dev/manual verification of the backend requires the
real PostgreSQL + NATS containers from `docker/docker-compose.yml`; there's no in-memory/mocked
path for those two dependencies (the app is wired to Flyway+PostgreSQL — see `application.yml`).

Swagger UI is served at `/swagger/views/swagger-ui` once the backend is running.

## Backend architecture (`backend/src/main/java/.../blackhole/backend/`)

Per-package detail below is intentionally short — full detail for each loads automatically via
`.claude/rules/*.md` (path-scoped) when you actually open a file in that package, instead of
consuming context in every session regardless of what you're touching.

- **Authentication: Entra ID JWT bearer on every route except `/health`.** Roles come from the
  `groups` claim (Entra Free) for humans and `roles` for app-only tokens (PROXY, METRICS);
  `@Secured` is on every controller and a route-coverage test enforces the matrix. The actor
  is the token's `oid`, stored as `recorded_by_oid`; client actor fields are ignored. Local dev
  uses the real Entra dev registration (`~/.config/blackhole/entra-dev.env`), tests self-signed
  JWTs. Never add an open route or an auth-off switch. Detail: `.claude/rules/security.md`.
- **`controller/`** — REST controllers; class-level `@Version` API versioning convention.
  Detail: `.claude/rules/controller-versioning.md`.
- **`events/`** — Domain event bus over NATS JetStream (subjects `blackhole.events.<type>`, stream `BLACKHOLE_EVENTS`). There is
  deliberately no generic connector/webhook/signal-ingestion framework anymore (removed
  2026-07-09 to keep the surface simpler) — external-system integration would need to be designed
  fresh if it comes back. Detail: `.claude/rules/events.md`.
- **`elo/`** — Dual-ELO engine (chat/gameplay scoring, auto-punishment thresholds).
  Detail: `.claude/rules/elo.md`.
- **`appeal/`** — Appeal workflow (auto- vs manual-ban wait periods).
  Detail: `.claude/rules/appeal.md`.
- **`evidence/`** — Evidence attached to punishments (files, anticheat, chat, links); lives as
  long as the punishment. Detail: `.claude/rules/evidence.md`.
- **`evasion/`** — Ban-evasion detection via hashed IP correlation.
  Detail: `.claude/rules/evasion.md`.
- **`database/`** — Hibernate/JPA + Flyway schema ownership.
  Detail: `.claude/rules/database.md`.
- **`imports/`** — Vanilla ban-list import support (`VanillaImportController`).

## Cross-cutting conventions

- **SSRF hardening on caller-controlled URLs.** There is no caller-controlled-URL feature in the
  codebase right now (the connector/webhook framework that used to be the example was removed
  2026-07-09). If one is added back, it needs equivalent SSRF hardening to what
  `WebhookUrlValidator` used to provide — validate before ever issuing a server-side request to a
  caller-supplied URL, don't skip it because "it's just an admin-supplied value."
- **Rate limiting stays out of the app.** Existing app-level rate limiting (e.g. report
  submission) is a deliberate exception already in place; don't add further app-level rate
  limiters for new endpoints — the operator handles that at the infra layer.
- **`@Transactional` is currently unusable** in this codebase due to a bean-ambiguity issue.
  Don't reach for it as the default transaction-management approach without checking the current
  state of that ambiguity first — several fix attempts have already been tried and reverted.
- Config surface is env-var driven with local-dev defaults inline in `application.yml`
  (`${ENV_VAR:default}`); when adding a new tunable, follow that pattern and document intent in
  a comment there rather than in code, consistent with how ELO/appeal/evasion config is
  documented today.

## Contributing / commit conventions

- Commit messages and PR titles **must** follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `chore:`, ...) — enforced in CI (`.github/workflows/pr-lint.yaml`, via a
  shared reusable workflow) and consumed by `release-please` to determine version bumps and
  generate `CHANGELOG.md`.
- This repo only allows squash merges, so the PR title becomes the commit on `main` and is
  linted identically to individual commits (`commitlint.config.mjs`).
- Allowed types: `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `revert`,
  `style`, `test`. Header max length is 100 chars.
- A `!` after the type (e.g. `feat!:`) signals a breaking change for release-please's major-version bump.
- Do not add a `Co-Authored-By: Claude` (or any other AI co-author) trailer to commits in this repo.

## Working with Claude Code on this repo

- At the start of a substantive session (implementation/design/debugging, not a quick question),
  ground yourself before acting: check Outline for anything relevant to banning/punishment
  systems and Blackhole itself (roadmap, design docs, decisions), read the actual current
  codebase rather than relying on prior context, and pull current web knowledge for whatever
  specific library/API/security topic the task touches rather than relying solely on training
  data. This keeps reasoning grounded in current state instead of stale assumptions.
- **OpenSpec is the primary planning workflow** for non-trivial feature work:
  `/opsx:propose` → review → `/opsx:apply` → `/opsx:archive` (`/opsx:explore`, `/opsx:update`
  as needed; CLI `openspec`, validate with `openspec validate --all --strict`). Current
  behavior lives in `openspec/specs/<capability>/spec.md`, in-flight work in
  `openspec/changes/<change>/`. `openspec/config.yaml` (`context`) holds the ratified
  architecture/product principles (GDPR-by-design, Entra sign-in, layering, dual-ELO,
  infra-owns-rate-limiting) — a proposal or design must not contradict it. Trivial fixes and
  small refactors don't need a change; use judgment. `.claude/PROGRESS.md` stays the lighter
  session-level continuity log — it complements OpenSpec changes, it doesn't replace them.
- **Session start sync (worktrees only).** If the session is running in a git worktree, before
  doing anything else: `git fetch origin` + `git pull` on `main` (in the main checkout, not the
  worktree), then hard-reset the worktree branch to match `main`
  (`git reset --hard origin/main` from inside the worktree, after first checking `git status` and
  setting aside any uncommitted work per the destructive-action rules above). This keeps every new
  session starting from the true current `main` instead of a stale worktree snapshot. Skip this
  step when working directly in the main checkout.
- **Commit as you go.** After finishing a task or a coherent chunk of work within a session, create
  a small commit for it immediately rather than batching everything into one commit at the end —
  each commit message follows the Conventional Commits rules above. Don't wait until the whole
  session's work is done to start committing.
- **Open a PR at session end.** When a session's work is complete (or you're wrapping up for now),
  push the branch and open a PR against `main` rather than leaving finished work uncommitted on a
  local/worktree branch. Confirm with the user before pushing/opening the PR if there's any doubt
  about whether the work is actually ready.
- **Context management.** `.claude/PROGRESS.md` is the state that survives a context reset,
  `/compact`, or a fresh session — the conversation itself does not. Check it at the start of a
  session alongside the grounding step above, and keep it current: move a task into "Active" when
  you start it, into "Recently completed" when you finish it, and if you stop mid-task, leave
  enough detail (files touched, why, what's left) that a fresh session with no memory of this one
  can resume. A `SessionStart`/`compact` hook (`.claude/hooks/reinject-progress-context.sh`)
  re-injects it automatically after compaction. This file is for resuming work, not narrating it —
  keep entries short; design rationale belongs in commits/PRs/Outline.
- **Keep this file lean.** CLAUDE.md loads in full every session; length past ~200 lines measurably
  hurts adherence. Before adding a new bullet, ask "would removing this cause a mistake?" — if not,
  it doesn't belong here. Domain knowledge that's only *sometimes* relevant (a specific package's
  internals, a review checklist) belongs in a skill (`.claude/skills/`, loaded only when relevant)
  or a path-scoped rule (`.claude/rules/*.md` with `paths:` frontmatter), not appended here. A rule
  that must always hold (never edit an applied Flyway migration, no `--force`/`--no-verify`)
  belongs in a `.claude/settings.json` hook if it can be checked deterministically — see the hooks
  already there — rather than relying on this file's prose, which is advisory, not enforced.
