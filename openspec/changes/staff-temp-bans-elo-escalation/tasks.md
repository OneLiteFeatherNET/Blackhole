# Tasks

## 1. Sections

- [x] 1.1 Append a Flyway migration `V2__punishment_sections.sql` (create + seed punishment_sections) (chat/MUTE, network/BAN with design defaults); verify with `migration-checker` agent and an integration test that a fresh PostgreSQL lists both sections
- [x] 1.2 Append `add-servers-to-sections`; validate SERVER enforcement patterns (required, not for other kinds, no match-all); verify service unit tests for each rule
- [x] 1.3 Add `section/` feature package (entity, repository, sealed `SectionDTO`, service with threshold validation, `SectionApi` + thin controller, `section.changed` event + Redis pub/sub notification, shared-cache entry); verify with service unit tests for validation rules and an integration test for CRUD and disable

## 2. Per-section ELO

- [x] 2.1 Append `create-elo-scores` and `add-section-to-elo-events`; replace track columns usage with per-section scores (missing row = base score) in the ELO write path; verify unit tests for independence and base-score reads
- [x] 2.2 Introduce the `EscalationPolicy` interface with the default threshold policy (soft = crossing rule, permanent = below threshold and no active permanent punishment); verify unit tests for crossing, already-escalated, pardoned-below-threshold and double-threshold cases
- [x] 2.3 Map toxicity flags (`blackhole.elo.chat.section`) and report categories (`blackhole.elo.report.category-sections`) to sections; remove `perma-ban-template.*` config; document new keys in `application.yml`; verify report and toxicity service tests
- [x] 2.4 Rework `GET /elo/{owner}` (per-section scores + thresholds, base scores instead of 404), history `section` filter and new `POST /elo/{owner}/adjust`, with `EloApi` interface and sealed DTOs; verify controller integration tests

## 3. Templates and punishments

- [x] 3.1 Append `add-section-columns-to-templates` and `add-section-columns-to-punishments`; enforce key uniqueness (409), existing section, positive duration and non-negative eloCost in the template service; verify unit tests for each validation error
- [x] 3.2 Append `create-template-steps` (+ `escalation_group`, `escalation_window` columns and index `punishments(owner, escalation_group, created_at)`); implement step selection (window, revoked/appeal-lifted excluded, expired counted, clamp to last step, shared groups) and record group/step/stepCount/cost on the punishment; verify unit tests for each scenario of "Escalation step selection" and "Shared escalation groups"
- [x] 3.3 Append `create-active-punishments`; rotate only the target section's slot on apply; fix expiry-on-read publishing the section; verify tests "mute does not lift a ban" and "expired mute clears chat key"
- [x] 3.4 Implement `POST /punishment/apply` returning the apply result (step, score before/after, escalation) and record the deducted cost on the punishment; create system permanent punishments without template; verify integration test covering apply -> deduction -> escalation
- [x] 3.5 Implement `POST /punishment/revoke` per section with pardon-score reset for permanent punishments; remove old apply/revoke paths; verify tests for temp revoke, permanent revoke reset and 404
- [x] 3.6 Apply appeal ELO effects (PARDON_RESET, APPEAL_REFUND) in the appeal decision path; verify appeal service tests for full lift, reduction and denial
- [x] 3.7 Switch the Redis mirror to `blackhole:punish:<section>:<owner>` messages with section + enforcement; verify a Redis-backed integration test for set/TTL/clear
- [x] 3.8 Append `drop-legacy-elo-columns` and `drop-legacy-profile-slots` once no code reads them; verify `hbm2ddl validate` passes on startup

## 4. API client

- [x] 4.1 Export the new OpenAPI spec to `client/specs/blackhole-api-<next>.yml`, bump `inputSpec` in `client/build.gradle.kts`; verify `./gradlew :client:build`

## 5. Velocity plugin

- [x] 5.1 Load sections (shared cache, API fallback) and reload on `section.changed`; per-section sync cache, login (BAN sections) and chat (MUTE sections) enforcement, cross-proxy kick; verify unit tests for enforcement decisions with a stubbed mirror
- [ ] 5.2 Enforce SERVER sections on `ServerPreConnectEvent` (glob match on server name) and move players on a matching server to the fallback server when a SERVER ban arrives (disconnect if none); verify unit tests for pattern matching and manual run with two backend servers
- [ ] 5.3 Rewrite `/ban` (BAN + SERVER sections), `/mute`, add `/punish` with template-by-key parsing, section filtering, i18n errors (no null/NPE) and result messages incl. step (n/m), ELO change and permanent escalation; remove `--server`; verify parser unit tests and manual run via `:velocity:runVelocity`
- [ ] 5.4 Rework `/unban`/`/unmute` (optional section incl. SERVER sections for unban, offline targets via resolver, `blackhole.pardon.permanent`) and add `/elo <player>`; verify manual run and i18n keys present in both `blackhole_en.properties` and `blackhole_de.properties`

## 6. Documentation and integration

- [x] 6.1 Update README (staff workflow, sections, permissions table), `.claude/rules/elo.md`; verify docs match command syntax in code
- [ ] 6.2 End-to-end check against local PostgreSQL + Dragonfly + RabbitMQ: staff temp-bans climb the steps until escalation, permanent ban enforced on login, a SERVER-section ban blocks only matching servers, revoke resets score; record the result in the PR description

## Workflow follow-up

- Archive the change with `/opsx:archive` after review and merge, so the deltas land in `openspec/specs/`.
