## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout. One code PR for the change, with one
  commit per group (all of type `feat`, scope `audit`).
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state,
  fresh fixtures per test, one behaviour per test, no real network (the webhook is a fake transport).
- Commit per type. No trailers of any kind beyond the ones the repository's rules require.
- The migration number is the next free Flyway version at implementation time (V27 on main today).
- Never edit an applied Flyway migration. Never use `--force` or `--no-verify`.

## 1. Backend: table and recorder

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/audit/StaffAuditRecorderTest.java`
  asserts that a recorded action is stored once with its surface, action and target, and that a failing
  insert is reported to the caller as `Unrecorded` and does not throw.
- [ ] 1.2 Red: `.../audit/SurfaceResolverTest.java`: a `PROXY` with `onBehalfOf` gives `IN_GAME`; a `DISCORD`
  with `onBehalfOf` gives `DISCORD`; a human with the panel header gives `PANEL`; a human without it gives
  `API`; a `surface` field in a body is ignored (the resolver takes no body).
- [ ] 1.3 Red: `.../audit/AuditPayloadGuardTest.java`: a payload built from a case that has a Ban ID, an IP
  and a raw UUID contains none of them (the guard scans the built payload map).
- [ ] 1.4 Add `backend/src/main/resources/db/migration/V<next>__staff_audit.sql`: `staff_audit` with the
  columns of `design.md` decision 1, identity `seq`, indexes `(occurred_at, seq)`, `(actor_oid, seq)`,
  `(action, seq)`. No foreign keys.
- [ ] 1.5 Add the `audit/` package: `StaffAuditEntity`, `StaffAuditRepository` (JDBC, append and cursor read),
  `StaffAuditRecorder` (returns `Recorded` or `Unrecorded`, logs without the payload, counts failures),
  `Surface` and `AuditAction` (fixed vocabulary of action names), `SurfaceResolver`, `AuditPayloadGuard`.
- [ ] 1.6 Add `blackhole.audit.*` to `application.yml` with env defaults and a comment per key
  (`retention`, `sweep-interval`, `panel-base-url` from `BLACKHOLE_PANEL_URL`).

## 2. Backend: record the staff writes

- [ ] 2.1 Red: one service test per write group that asserts exactly one entry for a successful action and
  none for a refused one: punishment (apply, revoke, amend), case (update, note), appeal (review), report
  (resolve), evidence (add, delete), ELO (adjust), kick (single, mass), client rules, sections, templates,
  imports, staff links (admin unlink), Discord links (admin remove).
- [ ] 2.2 Call `StaffAuditRecorder.record` from each of those services after the action succeeds. The
  controllers stay thin: the call sits in the service, not in the controller.
- [ ] 2.3 Set the `X-Audit: unrecorded` response header in the thin controller mapping when the recorder
  answered `Unrecorded`, via one shared helper (test: the header appears when the recorder fails).

## 3. Backend: event, read route, sweeper, anonymization

- [ ] 3.1 Red: `.../audit/StaffActionEventTest.java`: the published payload has the version 1 fields and no
  free text; the event is published after the row exists (fake transport and fake repository, ordering asserted).
- [ ] 3.2 Publish `blackhole.events.staff.action` through `DomainEventPublisher` with the payload built by
  `AuditPayloadGuard`.
- [ ] 3.3 Red: `.../audit/AuditReadServiceTest.java`: newest first, `limit` bounds, an opaque cursor, no overlap
  between pages, filter by action and actor.
- [ ] 3.4 Add `AuditApi` (OpenAPI annotations on the interface, `@Secured(Roles.ADMIN)`), thin `AuditController`
  on `/admin/audit`, `AuditService` answering `AuditDTO.Error` for expected failures, sealed `AuditDTO`
  (`Page`, `Entry`, `Error`).
- [ ] 3.5 Red: `.../audit/AuditRetentionSweeperTest.java` with a fixed `Clock`: a row one year and one day old is
  deleted, a row one day younger stays, the batch size is respected.
- [ ] 3.6 Add `AuditRetentionSweeper` (scheduled, batches of 500).
- [ ] 3.7 Red: `.../security/StaffAnonymizeClearsAuditNameTest.java`: anonymizing a staff member clears
  `actor_name` on that oid's rows and keeps `actor_oid`. Then wire it into `StaffActorService`.

## 4. Client spec

- [ ] 4.1 Add the audit route and the `AuditDTO` schemas to the next client spec version (0.0.44 in
  `client/build.gradle.kts` today; the next version at implementation time). Bump `inputSpec`, run
  `./gradlew :client:openApiGenerate` and commit the regenerated sources only from that task.
- [ ] 4.2 Red then green: a client-side test that the generated `AuditApi` exists and parses a page with
  `nextCursor` null.

## 5. Discord bot: webhook delivery

- [ ] 5.1 Red: `discord/src/test/java/.../audit/WebhookUrlTest.java`: accepts `discord.com` and `discordapp.com`
  webhook URLs; refuses `http`, other hosts, and a missing token; the error text names the variable, not the URL.
- [ ] 5.2 Red: `.../audit/AuditWebhookPosterTest.java` with a fake transport: `429` with `retry_after` 2.5 is
  retried after 2.5 s (fake scheduler, no sleep); `500` is retried with growing delays capped at five minutes;
  `404` is not retried, is counted and is acknowledged; no log line contains the URL.
- [ ] 5.3 Red: `.../audit/StaffActionConsumerTest.java`: entries are processed one at a time in order; an ack
  happens only after a success or a final rejection.
- [ ] 5.4 Red: `.../audit/AuditEmbedTest.java`: the embed has the action title, the fields, the Discord
  timestamp and the link `<panelUrl><panelPath>` ending the message, and the footer holds the `auditId`;
  the renderer takes only the event fields, so no other text can reach the message.
- [ ] 5.5 Add `WebhookUrl`, `AuditWebhookPoster`, `StaffActionConsumer` (durable `discord-audit`, `MaxAckPending` 1,
  `DeliverNew`), wire into `Main` next to `NatsConsumer`, add `DISCORD_AUDIT_WEBHOOK_URL` to `BotConfig` with the
  startup failure of `design.md` decision 5.
- [ ] 5.6 Metrics: `discord_audit_posted_total`, `discord_audit_retried_total`, `discord_audit_rejected_total`
  (labels: outcome only, never the URL or the id).

## 6. Velocity hook-in

- [ ] 6.1 Red: a Velocity test (existing command test harness) that the kick and punish commands send `onBehalfOf`
  for the staff member on every call, so the backend can derive `IN_GAME`. Add only the missing field if a command
  does not send it. No other Velocity change.

## 7. Web: Audit tab

- [ ] 7.1 Red: `web/src/pages/admin/AuditTab.test.ts` (or the existing admin tab test pattern) that a MODERATOR
  does not see the tab, an ADMIN sees rows newest first, a next page appends once, and the target links open the
  case or player page. Mock the API client; no network.
- [ ] 7.2 Add the Audit tab, the `/admin/audit` route (`meta: { admin: true, adminTab: 'audit' }`), the API method
  in `web/src/api/blackhole.ts`, and the i18n keys in every locale file (`i18n.keys.test.ts` must pass).
- [ ] 7.3 Run `npm --prefix web test` and `npm --prefix web run typecheck` (or the project's existing script).

## 8. Helm and deployment

- [ ] 8.1 `deploy/charts/blackhole`: document `DISCORD_AUDIT_WEBHOOK_URL` in the discord Secret keys in `README.md`
  and in the values comment next to `discord.existingSecret`; add `blackhole.audit.*` to the backend ConfigMap
  values where an operator tunes them. Update `values.schema.json` only if a new value is added.
- [ ] 8.2 Red then green: the chart tests in `deploy/charts/blackhole/tests` render the discord Deployment with the
  secret reference and fail when `existingSecret` is empty and the bot is enabled.
- [ ] 8.3 Bump `secretRevision` in the chart's documentation for the discord Secret change (operator note in README).

## 9. Open the code pull request

- [ ] 9.1 Open the code pull request titled `feat(audit): log staff actions to a discord channel` against `main`
  from the branch of groups 1 to 8, with the body from `.github/pull_request_template.md` (Proposed changes as a
  short story: what staff see today and what the channel shows; Types of changes: New feature). Run `gh pr checks --watch`.
  Do not merge.
