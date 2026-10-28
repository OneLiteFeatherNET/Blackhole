## Why

Today a staff punishment can only be undone or left alone. A ban that was too long, a mute that should
have been permanent after a second offence, or a reason typed with the wrong words cannot be corrected.
The moderator either revokes and re-applies, which writes a second punishment and a second escalation
step, or leaves a wrong record in place. Both are worse than one recorded correction with a reason. Players
who appeal also see an expiry that the staff member has since changed, and nothing says why.

## What Changes

- **Amend an active punishment.** A staff member can change an active ban or mute in one of these ways:
  - shorten it to a later expiry (still in the future);
  - extend it to a later expiry;
  - make it permanent;
  - make a permanent one temporary, with a new expiry;
  - change its reason text;
  - change its template reference (the template's reason and escalation group are kept; see `design.md`).
  Each amendment needs at least one change and a **mandatory justification** of 10 to 500 characters.
- **Every amendment is recorded.** An amendment row keeps the actor (the token's `oid`), the justification, the
  state before and the state after. It is a case timeline event `punishment.amended` with both states. The
  record is append-only.
- **ELO.** An amendment never changes an ELO score by itself. The one exception is a permanent punishment made
  temporary, which resets the score to the section's pardon score, as an appeal duration reduction already does
  (see `design.md` decision 5). Refunds of temporary costs stay with revoke and appeal.
- **Permissions.** A MODERATOR may shorten a temporary punishment and change its reason or template. An ADMIN may do
  every change, including extending, making permanent and making a permanent one temporary. The server enforces this;
  the panel and the command only hide what the server would refuse.
- **Concurrency.** The punishment carries a `version`. `GET /punishment/{identifier}` returns it as `ETag`, and
  an amendment requires `If-Match` (412 when stale, 428 when missing) and an `Idempotency-Key`, as in
  `standardize-api-pagination-and-problems`.
- **Entry points:**
  - Panel: an "Amend" action on the player page (per active punishment) and the case page, with a dialog that
    shows before and after.
  - In game: `/blackhole <user> amend duration <1d|permanent> <justification>` and
    `/blackhole <user> amend reason <template> <justification>` (permission `blackhole.amend`, plus the role
    rules above).
- **Velocity and the ban screen.** The backend publishes `punishment.updated` with the new state after an
  amendment, as it already does for re-templating. Velocity updates its mirror, so the ban screen shows the new
  expiry or the new reason on the next login. An online player whose network ban was made permanent or extended
  is disconnected with the updated screen, the same way a new ban is enforced (see `design.md` decision 8).
- **Appeals.** An amendment while an appeal is open is allowed. The appeal review shows the current state and the
  amendment history. The decision applies to the punishment as it stands at decision time. The minimum wait is
  still measured from the punishment's creation, and an amendment never restarts it. A severity change is read at
  review time: a permanent ban made temporary by amendment can be fully lifted, and a temporary one made permanent
  cannot be lifted completely (the existing SEVERE rule).
- **BREAKING (generated client and Velocity plugin):** not breaking for existing routes. The client gains new
  routes, a `version` field on the punishment and the amendment schemas. An older Velocity build ignores them and
  keeps working; the amend command needs the new client, so the Velocity PR ships with it.

## Capabilities

### New Capabilities

- `punishment-amendment`: the amendment of an active punishment, its changes, justification, permission rules, the
  concurrency and idempotency rules, the audit record and the amendment routes.

### Modified Capabilities

- `punishment-management`: the `version` on a punishment; the amendment events (`punishment.updated`, and the case
  event); the template reference rule on amendment.
- `proxy-enforcement`: the `/blackhole ... amend` command and its permission; the handling of `punishment.updated`
  for a changed expiry or reason, and the re-enforcement of online players affected by a ban amendment.
- `elo-scoring`: amendments change no score, except the pardon reset of a permanent punishment made temporary.
- `admin-web-ui`: the amend action and its dialog on the player page and the case page; the amendment history.
- `appeal-workflow`: decisions apply to the amended state; the minimum wait is not restarted by an amendment; the
  severity tier is read at review time.

## Out of scope

- Amending a warning or a kick. A warning is corrected by an appeal; a kick is a moment that cannot change.
  Amendments apply to `PUNISHMENT` rows only.
- Lifting a punishment by amendment. Lifting stays revoke and appeal, so the lift has its own audit paths and ELO
  refunds.
- Amending the amount of an ELO cost, or recalculating ELO for past amendments.
- Changing the escalation group or the template's escalation count after the fact. The group recorded at apply time
  is kept.
- Bulk amendment. One punishment per request.
- Any change to identity or auth. The actor is the token's `oid`. The in-game path is answered in `design.md`
  open question 1, which is about how a proxy-relayed actor's role is established.
- Rate limiting. Infra owns rate limiting (`config.yaml` principle 5).

## Stacked pull requests and commit types

Ships as: feat(punishment): amend an active punishment with a recorded justification

The split is in `tasks.md`. One type per commit: `feat` for behaviour, `docs` for the rule file and the proposal.

1. `feat(punishment): add the amendment record and the punishment version` (migration, model, ETag)
2. `feat(punishment): add the amendment routes and the amendment audit`
3. `feat(elo): reset the score when a permanent punishment is made temporary` (the one ELO rule, its own PR so it
   can be reviewed with the appeal path)
4. `feat(velocity): apply amended punishments and add the amend command`
5. `feat(web): add the amend dialog and the amendment history`
6. `docs(openspec): propose amending a punishment` (this change; the archive commit reads
   `docs(openspec): archive add-punishment-amendment`)

## Impact

- **Backend**: `punishment/` (service, DTO, routes), `casetimeline/` (`punishment.amended` case event), `elo/` (the
  pardon reset call only), `appeal/` (the decision reads the amended state; no new branch), `events/` (no new subject:
  `punishment.updated` is reused), `idempotency` (the existing store from `standardize-api-pagination-and-problems`).
- **Database**: one new Flyway migration `V<next>__punishment_amendment.sql`, the next free version at implementation
  (main is at V27 today). Adds `punishments.version` and the `punishment_amendment` table. Additive.
- **API**: `GET /punishment/{identifier}`, `POST /punishment/{identifier}/amendments`,
  `GET /punishment/{identifier}/amendments`. `client/specs` gets the next free version.
- **Velocity**: the amend command, the mirror update on `punishment.updated` (already subscribed), the re-enforcement
  of an online player on a ban amendment.
- **Web**: an amend dialog and an amendment list in the case and player pages.
- **Docs**: `.claude/rules/amendment.md` (path-scoped) and one line in `CLAUDE.md`.

## Prerequisite

`standardize-api-pagination-and-problems` defines the `ETag` / `If-Match` and `Idempotency-Key` rules this change uses.
It is not archived or merged yet. Its design D17 lists the resources that carry a version; this change adds
`punishment` to that list (`design.md` decision 2). Until that change is archived, the code PR of this change
waits for it.
