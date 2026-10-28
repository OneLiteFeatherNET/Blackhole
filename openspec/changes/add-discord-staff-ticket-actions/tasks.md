## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state, fresh
  fixtures per test, one behaviour per test, no real Discord or network (a fake gateway and a fake backend client).
- Commit per type. No trailers beyond what the repository's rules require.
- The migration number is the next free Flyway version at implementation time (V27 on main today; the audit
  migration of `add-staff-audit-log` may take the next one first).
- Never edit an applied Flyway migration. Never use `--force` or `--no-verify`.
- Prerequisite: `add-staff-audit-log` group 1 (the recorder) is merged, or groups 5 and 7 of this change wait for it.

## 1. Backend: role snapshot and the on-behalf rule

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/security/RoleSnapshotTest.java`: a
  verified HUMAN request with MODERATOR stores `roles` and `roles_seen_at` from the fixed clock; a SERVICE request
  stores nothing; only the `Roles` names are stored.
- [ ] 1.2 Red: `.../security/DiscordOnBehalfTest.java`: `DISCORD` with `onBehalfOf` on a route that is not one of the
  four is refused (400); `DISCORD` on one of the four without `onBehalfOf` is refused (403); a linked oid with a
  fresh MODERATOR snapshot is attributed; no link gives `STAFF_NOT_ELIGIBLE`; a snapshot older than the window
  (fixed clock, `PT24H` plus one second) gives `STAFF_ROLE_STALE`; a VIEWER snapshot gives `STAFF_NOT_ELIGIBLE`.
- [ ] 1.3 Add `V<next>__staff_ticket_actions.sql`: `staff_actor.roles varchar(128)`, `staff_actor.roles_seen_at bigint`,
  `discord_ticket.freeze_source varchar(8)`, `UPDATE discord_ticket SET freeze_source = 'LEAK' WHERE frozen`.
- [ ] 1.4 Extend `StaffActorService` / `ActorResolver` to write the snapshot for HUMAN callers; add the
  `blackhole.discord.staff-role-max-age` property (default `PT24H`) with its comment in `application.yml`.
- [ ] 1.5 Add `StaffDelegation` (the check of decision 1: link, snapshot, window) and call it from `Actor.attribution`
  for the `DISCORD` case on the four routes only.

## 2. Backend: routes and the appeal rule

- [ ] 2.1 Red: `.../appeal/service/AppealReviewDiscordTest.java`: an accept by a linked MODERATOR is attributed to that
  oid; the issuer of the punishment is refused with 403 (self-review uses the attributed oid); a second decision is 409;
  a reject without a player message is 400; a UUID or Ban ID in the message is 422 and nothing is stored.
- [ ] 2.2 Add `onBehalfOf` (nullable UUID) to `AppealReviewDTO`, wired to the delegation check in `AppealApi.review`
  (`@Secured({Roles.MODERATOR, Roles.ADMIN, Roles.DISCORD})`, the DISCORD case only with `onBehalfOf`).
- [ ] 2.3 Red: `.../casetimeline/service/CaseClaimDiscordTest.java`: a claim with `onBehalfOf` sets the assignee to the
  staff oid; a stale `expectedVersion` is 409; an unknown assignee is 422.
- [ ] 2.4 Add `onBehalfOf` to `CaseDTO.UpdateRequest` and the delegation check to `CaseApi.update`.
- [ ] 2.5 Red: `.../discord/ticket/StaffFreezeTest.java`: lock sets `frozen` with source `STAFF`; a repeat is a no-op
  answering the current state; unlock needs a `STAFF` freeze or a MODERATOR panel call; a `LEAK` freeze is not lifted
  by unlock (409 `FREEZE_BY_LEAK`).
- [ ] 2.6 Add `StaffTicketApi` (`PUT /case/{reference}/ticket/freeze`, OpenAPI on the interface, thin controller) and
  `StaffTicketService` (answers `StaffTicketDTO.Error`).
- [ ] 2.7 Red: `.../discord/ticket/TicketCloseDiscordTest.java`: close with `onBehalfOf` records the close under the staff
  oid; a close before the decision is 409 INVALID_STATE and the staff member is not recorded.
- [ ] 2.8 Add `onBehalfOf` to `DiscordTicketDTO.CloseRequest`.
- [ ] 2.9 Red: `.../discord/controller/StaffLinkLookupTest.java`: `GET /discord/staff-links/{discordUserId}` answers the
  oid and the freshness flag for a live staff link, 404 for none, and never returns a Discord id in the body.
- [ ] 2.10 Add the lookup route to `DiscordStaffLinksController` (DISCORD role) with OpenAPI annotations on its interface.
- [ ] 2.11 Call `StaffAuditRecorder.record` (surface `DISCORD`) from the four routes after success (test: one entry per
  success, none per refusal). Depends on `add-staff-audit-log` group 1.

## 3. Client spec

- [ ] 3.1 Add the new routes, the `onBehalfOf` fields and the error codes to the next client spec version (0.0.44 in
  `client/build.gradle.kts` today; the next version at implementation time). Bump `inputSpec`, run
  `./gradlew :client:openApiGenerate`, commit the regenerated sources in that task only.
- [ ] 3.2 Red then green: a client test that the generated API exposes the freeze route and the lookup and that
  `onBehalfOf` serializes as an optional field.

## 4. Discord bot: control message and buttons

- [ ] 4.1 Red: `discord/src/test/java/.../ticket/StaffControlRendererTest.java`: the message shows the case reference, the
  state line for each ticket state, the claimed name, and only the buttons the state allows (Accept and Reject disabled
  after a decision, Unlock shown only when locked, Close shown only after the decision, Open in panel always).
- [ ] 4.2 Red: `.../command/StaffButtonHandlerTest.java` with a fake gateway and fake backend: a press from an unlinked
  member gets an ephemeral hint to `/link` and no backend call changes state; a member without the staff role gets a
  refusal; a button id for another ticket is ignored; a backend 409 re-renders from the current state.
- [ ] 4.3 Red: `.../command/StaffDecisionModalTest.java`: reject with an empty message is not sent; the server's 422 text is
  shown in the modal's error.
- [ ] 4.4 Red: `.../sync/ControlMessageSyncTest.java`: a `case.*` event for the ticket's case re-renders the control message
  once; an event for another case does nothing; a Discord outage during the re-render is not retried in a loop.
- [ ] 4.5 Add `StaffControlRenderer`, `StaffButtonHandler`, the modal handler, the re-render on `case.*` in
  `CaseEventHandler`, and the staff lookup call. All behind `DISCORD_STAFF_ACTIONS` (default off).
- [ ] 4.6 Red then green: `.../command/OpenInPanelLinkTest.java`: the link is `<panelUrl>/cases/<reference>` and carries no
  Ban ID.

## 5. Web: Lock / Unlock on the case page

- [ ] 5.1 Red: `web/src/components/case/CaseLockControl.test.ts`: a MODERATOR sees Lock on an open ticket and Unlock on a
  staff-locked one; a leak freeze shows no unlock and says why; a 409 shows the server's text; no network (mocked client).
- [ ] 5.2 Add `CaseLockControl.vue` on the case page (`web/src/pages/cases/...`) and the client method for
  `PUT /case/{reference}/ticket/freeze` in `web/src/api/blackhole.ts`.
- [ ] 5.3 Show the Discord control state on the case page next to `TicketStatus.vue` (claimed by, locked, decided).
- [ ] 5.4 `npm --prefix web test` and `npm --prefix web run typecheck` (or the project's existing script) green; i18n keys
  added to every locale (`i18n.keys.test.ts`).

## 6. Helm, config and docs

- [ ] 6.1 Document `blackhole.discord.staff-role-max-age` in the backend ConfigMap values comment in
  `deploy/charts/blackhole/values.yaml`, and `DISCORD_STAFF_ACTIONS` in the discord `config` comment.
- [ ] 6.2 Red then green: a chart test that the discord Deployment renders `DISCORD_STAFF_ACTIONS` from `config` when set.

## 7. Open the code pull request

- [ ] 7.1 Open the code pull request titled `feat(discord): add staff ticket actions` against `main`, with the body from
  `.github/pull_request_template.md` (Proposed changes as a short story of the appeal in the channel; Types of changes:
  New feature). Run `gh pr checks --watch`. Do not merge.
