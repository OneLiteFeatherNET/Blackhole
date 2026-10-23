# Tasks

Six small stacked PRs on top of `add-appeal-portal`, each squash-merged; the PR title is the
Conventional Commit. Every backend group lands its own JUnit tests and every UI group its own Vitest
tests (F.I.R.S.T.: injected `Clock`, a manual scheduler the test advances, in-memory fakes behind
`DiscordGateway` and `BackendGateway`, no sleeps, no network, self-signed JWTs, Testcontainers for SQL).
A bug found while implementing is reproduced by a failing test first.

## 0. Bot MVP, shipped first (phase 0, 2026-10-25)

Stacked PRs: `feat(discord): add the discord account link api` (V14, client spec 0.0.25, Entra script), `feat(velocity): show a discord link code in game`, `feat(discord): run the discord bot as its own module`, `feat(discord): notify the team of new reports and appeals`, `feat(web): link a discord account from the account dialog`.

- [x] 0.1 `Roles.DISCORD`, `Blackhole.Discord` mapping (`BLACKHOLE_AUTH_MACHINE_ROLE_DISCORD`), matrix and route-coverage rows
- [x] 0.2 `discord_link` and `discord_link_code` (V14), `DiscordLinkService`, `/staff-discord-link`, `/player-discord-link`, `/discord/link`, `/admin/discord-link`; unit, integration and permission tests
- [x] 0.3 `docs/entra-setup.sh` and `.md`: app role, `Blackhole Discord (<env>)` registration, secret, consent
- [x] 0.4 `/blackhole discord` in the Velocity plugin
- [x] 0.5 The `discord` module: config, token provider, `/link`, `/unlink`, `/whoami`, Dockerfile, CI
- [ ] 0.6 Notifications for reports and appeals (queue consumer, read facade, embeds, edit on resolve)
- [ ] 0.7 Panel: account dialog section and admin list

## 1. Entra role and backend facade foundation (PR 1, `feat(discord)`)

- [ ] 1.1 Failing tests first: `Blackhole.Discord` maps to `Roles.DISCORD` from app-only tokens only, DISCORD allowed nowhere but `/discord/**`, staff and PROXY denied there
- [ ] 1.2 `Roles.DISCORD`, `BlackholeRolesFinder`, `@Secured` constants, matrix rows in `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest`
- [ ] 1.3 Migration `V16__discord_tickets.sql` (next free version): `case_participant`, `discord_ticket`, `discord_message_link` (the link table shipped in phase 0); migration test (constraints, cascade)
- [ ] 1.4 Extend `docs/entra-setup.sh` (app role, `Blackhole Discord (<env>)` registration, assignment, secret, `BLACKHOLE_DISCORD_*` env lines; still idempotent) and `docs/entra-setup.md` (objects table, where each value goes); `bash -n` and a dry read-through
- [ ] 1.5 Regenerate the client spec (`client/specs/blackhole-api-0.0.N.yml`, next free number, bump `inputSpec`) after PR 2; update `.claude/rules` (security matrix)

## 2. Ticket and ingest API (PR 2, `feat(discord)`)

- [ ] 2.1 Failing tests first: open (uniform failure incl. bound-to-other and leaked, statement rules, `availableFrom`, bound participant), author resolution table, dedupe keys, edit only when text differs, delete incl. `contentUnavailable`, rescan insert/edit/delete detection, completeness 409 vs success, `close` stores a protocol and answers its version (503 when storing fails), archive and delete refused with 409 `PROTOCOL_REQUIRED` without a current protocol, `protocol/ensure` after a late edit, staff links list, outbox cursor, link advances `relayed_seq`, leak-check match and non-match, no Discord id or Ban ID in events or logs
- [ ] 2.2 `discord/` backend package: `DiscordApi` + thin controllers (`@Version(ApiVersion.V1)`), sealed DTOs, `TicketService`, `DiscordIngestService` (CTE per batch item: event + link + cursor), `LeakCheckService` (reuses the portal change's leak CTE with source `DISCORD_BOT` and poster fields)
- [ ] 2.3 New payloads `ticket.opened`, `ticket.transcript_complete`, `ticket.archived`, `ticket.channel_deleted`, attachment/embed metadata in `message.added`; portal allow-list gets opened/closed only; redaction covers attachment metadata
- [ ] 2.4 Participant erasure route (`ADMIN`) and the player-side erase of the portal change clears the bound participant; staff-link confirm/unlink for Discord with the existing one-to-one rules
- [ ] 2.4a Wire the portal change's protocol generator into `POST /discord/tickets/{id}/close` (same request as the completeness proof), `protocol/ensure` (forced), and the `ARCHIVED`/`DELETED` state gates; `GET /discord/staff-links`
- [ ] 2.5 Domain events `discord.ticket_*` (no content) and a durable-queue note in `.claude/rules/events.md`; config `blackhole.discord.*` documented in `application.yml`
- [ ] 2.6 Regenerate the client spec; verify `./gradlew :backend:test :client:compileJava`

## 3. The `discord` module (PR 3, `feat(discord)`)

- [ ] 3.1 Failing tests first against fakes: open flow, crash between ticket and channel, relay order and cursor, no duplicate after restart, own-message skip, edit/delete mirror both ways, freeze/thaw, decision lock and archive, delete only when complete, startup catch-up, close rescan with retries, failure table (backend down, broker down, Discord error, token expiry, missing permission)
- [ ] 3.2 Module skeleton: `settings.gradle.kts` include, version catalog entries (`net.dv8tion:JDA:6.7.0` without voice, `com.rabbitmq:amqp-client:5.37.0`; re-check Maven Central and the two-week rule), Shadow jar, `application` main, env config with fail-fast validation, architecture test (core packages do not import `net.dv8tion`)
- [ ] 3.3 `auth` (client credentials token source, reuse or move the Velocity provider), `BackendGateway` adapter on the generated client
- [ ] 3.4 `DiscordGateway` interface, JDA adapter (intents, cache flags off, member cache none, slash commands `/appeal`, `/link`, `/unlink`, button and modal, ephemeral replies, permission overwrites), composition test without connecting
- [ ] 3.4a `StaffAccessReconciler`: member overwrites for staff who hold the role and have a live link (creation, interval, `staff-link.removed`), no role overwrite, tests for linked/unlinked/role lost/administrator
- [ ] 3.5 Core: `TicketLifecycle` (archive only with the protocol version, delete only after `protocol/ensure`), `SyncEngine` (inbound ingest, outbox relay, cursor poll, RabbitMQ consumer as hint), `CatchUp` (startup and close rescan), `ArchiveSweeper` (delete delay), `PermissionSelfCheck`
- [ ] 3.6 Health endpoint, metrics (counters for relays, ingests, catch-ups, incomplete transcripts, dropped leak candidates), JSON logs without message content

## 4. Leak scanning (PR 4, `feat(discord)`)

- [ ] 4.1 Failing tests first: candidate regex table (prefixed, grouped, separators, case, word boundary, bare words and hex numbers rejected, max five), scope (public channel, ticket category excluded, LIST, OFF), backend says no match, match deletes and notifies, already leaked, backend down bounded retry, no content in logs
- [ ] 4.2 `LeakScanner` over `DiscordGateway`/`BackendGateway`, public-channel detection from `@everyone` overwrites, edits scanned, bounded in-memory queue with the injected scheduler
- [ ] 4.3 Wire the freeze effect to the ticket channel through the existing `case.ban_id_leak_marked` consumer; verify end to end in the fake layer

## 5. Panel (PR 5, `feat(web)`)

- [ ] 5.1 API types and client methods; fake-client tests
- [ ] 5.2 Case page: ticket status block, Discord/author-trust labels, missing-transcript banner, poster info on the leak entry, participant erasure dialog; no Discord ids rendered
- [ ] 5.3 Account-link dialog: Discord `/link` hint, state and unlink
- [ ] 5.4 de/en strings, `i18n.keys.test.ts` parity, live regions, role gating tests; verify `npm test` and the type check

## 6. Deployment and docs (PR 6, `docs(discord)`, plus `ci(discord)` for the workflow lines)

- [ ] 6.1 `discord/Dockerfile`, compose service with env, `ci(discord)`: image build and smoke check beside web and portal in `.github/workflows/build-pr.yml`
- [ ] 6.2 `discord/README.md` and `docs/`: developer-portal setup (bot, `MESSAGE_CONTENT` intent), categories and staff role, permission list per category, env table, Entra script usage, attachments-vanish-with-the-channel note, channel topic text, operator edge rule for the bot ingress, infra only and no app limiter: `POST /discord/tickets` global 30 per minute and `POST /discord/leak-check` global 120 per minute answering 429 (suggested starting values; the bot treats 429 as backend unavailable), the 30 day archive deletion and its protocol precondition, linked-staff-only channel access (`/link` first)
- [ ] 6.3 Note in `.claude/PROGRESS.md` per PR as it lands

## 7. Verification and wrap-up

- [ ] 7.1 Manual check against a real test guild and the real stack: open a ticket, chat three ways (Discord, portal, panel), edit and delete, stop the bot, write meanwhile, restart and verify catch-up, decide the appeal, verify `ticket.transcript_complete`, the stored protocol (text and JSON, equal to the panel record) and the archive, an unlinked staff member cannot see the channel, edit a message in the archive, delete the channel after a short delay (protocol regenerated first) and read the history in panel and portal, post a Ban ID in a public channel (deleted, frozen), link a staff account; record the result in PR 3
- [ ] 7.2 `openspec validate --all --strict`; archive with `docs(openspec): archive add-discord-appeal-tickets` after the PRs merge
- [ ] 7.3 Open the pull requests as small stacked PRs (each squash-merged, title is the Conventional Commit): `feat(discord): add the discord role and ticket facade foundation`, `feat(discord): add the ticket and message ingest api`, `feat(discord): add the discord appeal ticket bot`, `feat(discord): remove ban ids posted in public channels`, `feat(web): show discord tickets on the case page`, `docs(discord): document and deploy the discord bot`
