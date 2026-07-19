# Blackhole Constitution

## Core Principles

### I. GDPR-by-Design, Single-Tenant
Blackhole serves exactly one Minecraft network per deployment. Personally identifiable
data (IPs, raw player UUIDs) must be hashed or tokenized wherever it is stored or
published. There is no multi-tenancy — if multiple networks are ever needed, that is
handled by running separate deployments, never by sharing one deployment across
networks. Do not add a `tenantId` or equivalent discriminator back into the schema or
API surface.

### II. No Auth Layer, Trust via Network Boundary (NON-NEGOTIABLE)
There is deliberately no JWT/`@Secured`/auth layer — every endpoint is open. The trust
model is that only trusted callers (admins/tools calling the REST API directly, or the
Velocity plugin acting on behalf of admins/moderators) can reach the API at all, enforced
at the network/deployment level (firewalling, private networking), not by the
application. Do not add `@Secured` or reintroduce micronaut-security for new endpoints
without an explicit, discussed decision to do so. There is also no per-actor identity
anywhere in the system — fields like `source`/`resolvedBy`/`reviewerId` are
client-supplied and unverified; do not design a feature around a per-user identity claim
existing yet.

### III. Layered Backend: Controller / Service / DTO Contract
Every backend feature package follows a strict layering: controllers are thin HTTP
adapters (routing/versioning + exactly one service call + response mapping, never a
direct repository dependency or business/validation branching); business and persistence
logic lives in a `@Singleton` service colocated in its own feature package; each resource
has one sealed marker DTO interface nesting its `CreateRequest`/`UpdateRequest`/
`Response`/`Error` variants, and a service answers with the `Error` variant instead of
throwing for an expected failure; OpenAPI (`@Operation`/`@ApiResponse`) annotations live
on a dedicated `*Api` interface, never directly on the controller. These conventions are
enforced by the `micronaut-controller-layer`, `micronaut-service-layer`,
`micronaut-dto-contract`, and `micronaut-openapi-contract` skills — consult them before
adding or reviewing an endpoint.

### IV. Dual-ELO Punishment Engine Stays Automatic
Punishment decisions are driven by separate `chatElo` and `gameplayElo` scores per
player. Reports and the pluggable chat-toxicity scorer feed deltas into these scores, and
crossing a configurable soft/hard threshold must keep auto-applying a temporary or
permanent punishment without requiring a moderator to be online. Any change to the ELO
engine or appeal workflow must preserve this "works without a human in the loop" property
and the structured appeal path that lets players contest both automatic and manual
punishments.

### V. No App-Level Hardening Where Infra Already Owns It
Rate limiting is deliberately kept out of the application except the one existing report-
submission limiter — do not add further app-level rate limiters for new endpoints; the
operator handles that at the infra layer. Conversely, any feature that issues a
server-side request to a caller-supplied URL (there is none today) must get
`WebhookUrlValidator`-style SSRF hardening before it ships — never skip it because "it's
just an admin-supplied value."

### VI. Schema Changes Are Append-Only, `@Transactional` Is Off the Table
Liquibase changesets under `backend/src/main/resources/db/changelog/` are append-only —
never edit an already-applied changeset. `@Transactional` is currently unusable in this
codebase due to a known bean-ambiguity issue with several already-reverted fix attempts;
do not reach for it as the default transaction-management approach without first checking
whether that ambiguity has actually been resolved.

## Development Workflow

Config is env-var driven with local-dev defaults inline in `application.yml`
(`${ENV_VAR:default}`); new tunables follow that pattern and are documented as a comment
there, not in code. Local verification of the backend requires the real MariaDB +
RabbitMQ containers from `docker/docker-compose.yml` — there is no in-memory/mocked path
for those two dependencies.

Spec-Driven Development (this toolkit) is the primary planning workflow for non-trivial
feature work: `/speckit-constitution` → `/speckit-specify` → `/speckit-plan` →
`/speckit-tasks` → `/speckit-implement`, with `/speckit-clarify`, `/speckit-analyze`, and
`/speckit-checklist` used as needed. `.claude/PROGRESS.md` remains the lighter-weight,
session-level continuity log (what's active *right now*, mid-task state) — it complements
spec artifacts under `specs/`, it does not replace them. Trivial fixes and small
refactors don't need a full spec cycle; use judgment.

## Governance

This constitution supersedes ad-hoc practice for anything it covers. `CLAUDE.md` at the
repo root remains the authoritative, always-loaded source for session mechanics (commit
conventions, session-start sync, when to open a PR); this document is the authoritative
source for product/architecture principles that a spec or plan must not contradict.
Amendments happen via `/speckit-constitution`, must be justified in the amendment itself,
and bump the version below per semantic versioning (MAJOR: principle removed/redefined
incompatibly; MINOR: new principle or materially expanded guidance; PATCH: wording/typo
fixes).

**Version**: 1.0.0 | **Ratified**: 2026-07-19 | **Last Amended**: 2026-07-19
