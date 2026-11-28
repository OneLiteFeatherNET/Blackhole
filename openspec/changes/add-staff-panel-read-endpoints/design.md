# Design

Companion to `restructure-staff-panel` (design decisions 7.3 and 8, and its inbox rules). Every endpoint below was
checked against the controllers on `origin/main` (commit `51dae31`). The restructure design lists six missing reads;
all six are missing. The terms version is optional there and is included here.

## Decision 1: seven read-by-id endpoints

| # | Method and path | `@Version` | `@Secured` (same as the list of the entity) | Path identifier | 200 body | 404 body | Version / ETag |
|---|---|---|---|---|---|---|---|
| 1 | `GET /appeal/{identifier}` | `V1` | `VIEWER` (list `GET /appeal`) | appeal UUID | `AppealDTO` (with `evidenceCount`, as the list fills it) | no body, as `POST /appeal/{identifier}/review` | none: no version column, see known gap 1 |
| 2 | `GET /exemption/reviews/{id}` | `V1` | `ADMIN` (list `GET /exemption/reviews`) | review UUID | `ExemptionDTO.ReviewResponse` (existing) | `ExemptionDTO.Error` `NOT_FOUND` | none, known gap 1 |
| 3 | `GET /area-bans/{id}` | `V1` | `VIEWER`, `PROXY` (list `GET /area-bans`) | the id the list returns (a string, the same one `POST /area-bans/{id}/lift` takes) | `AreaBanDTO.Response` (existing) | `AreaBanDTO.Error` `NOT_FOUND` | none, known gap 1 |
| 4 | `GET /intel/allow-list/{owner}` | `V1` | `VIEWER` (list `GET /intel/allow-list`) | owner hash: 128 hex characters (SHA-512), the pattern the other owner parameters use | `AllowListDTO.Entry` (existing, already a marker variant) | `AllowListDTO.Error` `NOT_FOUND` | none, known gap 1 |
| 5 | `GET /admin/audit/{auditId}` | `V1` | `ADMIN` (list `GET /admin/audit`) | audit UUID | `AuditDTO.Item` (existing; made to implement `AuditDTO`, wire unchanged) | `AuditDTO.Error` `NOT_FOUND` | not needed: audit rows are append-only and never change |
| 6 | `GET /admin/staff-actor/{oid}` | `V1` | `ADMIN` (list `GET /admin/staff-actor`) | staff object id (UUID) | `StaffActorDTO.Item` (existing; made to implement `StaffActorDTO` if it does not, wire unchanged) | `StaffActorDTO.Error` `NOT_FOUND` | none, known gap 1 |
| 7 | `GET /appeal-conditions/versions/{version}` (optional, included) | `V1` | `VIEWER` (list `GET /appeal-conditions/versions`) | integer, 1 or more | `AppealConditionsDTO.Response` (existing) | `AppealConditionsDTO.Error` `NOT_FOUND` | not needed: a saved version never changes, and the number is its identifier |

Rules that apply to every row:

- **Role.** The `@Secured` set is exactly the set of the list endpoint of the same entity. It is never wider. The
  check runs on each request, before the controller method (the URL and the route guard are not the authorization).
- **Order of answers.** A missing or invalid token gets 401. A token whose roles are below the minimum gets 403 before
  any lookup, so a `VIEWER` asking for an unknown id of an `ADMIN`-only entity gets 403, the same answer as for an
  existing id. Existence therefore does not leak to a role that may not see the entity. A role that may read the
  entity gets 404 for an unknown id and 200 for a known one.
- **403 for a VIEWER-minimum endpoint.** Every row with `VIEWER` as the minimum has no role below it except a token
  with none of the Blackhole roles. The test uses such a token for the "role below minimum" case.
- **Path identifiers.** Only identifiers the panel already shows to staff: UUIDs of appeals, reviews, audit entries and
  staff objects; the area ban id; the owner hash; an integer version. A raw player UUID, a name, an IP address, a
  Discord id and a Ban ID are never accepted as a path value. For row 4 a value that is not a 128-hex owner hash is
  answered 400 `VALIDATION` before any lookup, so a raw UUID or a name cannot be mistaken for an owner.
- **No secret in the response.** None of the seven returns a Ban ID, a raw player UUID or an IP address. Area bans
  carry only an ASN or a country code (`AreaBan.Kind`). The Ban ID is held in its own encrypted store (`banid`) and is
  not part of any appeal, case or exemption row, so `punishmentIdentifier` in `AppealDTO` is not a Ban ID.
- **DTOs.** Each endpoint reuses the entity's existing record. Where the record is not yet a variant of the entity's
  sealed marker, the record implements the marker; no field is added or removed, so the JSON is unchanged. The
  exception is `AppealDTO`: it is a plain record used by the list and by the review, and it stays one (its 404 has no
  body, as the review's 404 has none). Turning it into a sealed marker would change two existing wire shapes and is
  out of scope.
- **Controller shape.** Each controller method makes one call to a service method in the entity's own package, which
  returns the sealed variant (success or `Error`). The OpenAPI annotations go on the existing `*Api` interface.

### Decision 1a: what the design list had and what it did not

- Dropped: none. All six named reads are missing (checked by the route list of `backend/`).
- Added: none to the read-by-id set. The inventory rows that the restructure design marks "yes" were checked against
  the controllers and exist (for example `GET /case/{ref}`, `GET /punishment/short/{shortId}`, `GET /ipban/{id}`,
  `GET /kick/{id}`, `GET /rollback/{id}` under `/admin/rollback`, `GET /report/{identifier}`, `GET /evidence/{identifier}`,
  `GET /warn/{id}`, `GET /client-rule/{key}`, `GET /section/{key}`, `GET /template` by identifier, `GET /exemption/{owner}`).
- Not a read-by-id, and not added: the list filters that restructure phase 6 relies on. See known gap 3.

## Decision 2: the inbox gets a `changedByOthers` section

**Question.** Which cases and appeals count as "changed by others", from which data, and what does a row say?

**Decision.** `GET /inbox` (VIEWER, unchanged) gets one more section, `changedByOthers`, after the four existing ones.
The four existing sections, their order and their fields are unchanged.

- **Source.** The case timeline (`case_event`), not a new table. Cases are keyed by punishment identifier and appeals
  belong to one punishment, so both kinds of row come from the same events.
- **Rule.** For each punishment that has an event inside the window, take its latest event (by `seq`). The punishment
  is listed when that event is by a **verified staff actor** (`actorTrust = VERIFIED`, `actorOid` present) and that
  actor is not the caller (`Actor.oid()`). An event by a proxy, a system job, an unverified source or the caller
  itself does not list the row. If the latest event is a later change by the caller, the row is not listed.
- **Window.** A rolling 24 hours from the read, configured as `blackhole.inbox.changed-window` (ISO-8601 duration,
  default `PT24H`, env `INBOX_CHANGED_WINDOW`, with a comment in `application.yml`). The restructure design said
  "today"; a rolling window avoids a time zone parameter. Open question 1 asks the maintainer to confirm.
- **Kind.** `APPEAL` when the latest event is `AppealFiled` or `AppealDecided` (the appeal identifier is taken from
  that payload); `CASE` otherwise.
- **What changed.** An enum, never free text. `STATUS` (`StatusChanged`), `ASSIGNEE` (`AssigneeChanged`), `PRIORITY`
  (`PriorityChanged`), `TAGS` (`TagsChanged`), `NOTE` (`NoteAdded`, `NoteEdited`, `NoteRedacted`), `MESSAGE`
  (`MessageAdded`, `MessageEdited`, `MessageDeleted`, `MessageRedacted`), `APPEAL_FILED`, `APPEAL_DECIDED`; every other
  event type is `OTHER`.
- **Who.** `byName`: the staff display name from the staff actor, or `null` once that person has been anonymized. The
  panel shows "Former staff member" for `null`. No staff object id is returned in the row.
- **Shape.** Newest first (a feed of changes, not a queue). At most 10 items; `count` is the full number of rows and is
  not capped. Each item: `kind` (`CASE` or `APPEAL`), `id` (case short reference, or appeal UUID), `reference`
  (nullable short reference of the punishment), `change` (enum above), `byName` (nullable), `at` (epoch millis of the
  latest event).
- **Who may read.** The same role as the inbox (`VIEWER`). It is read-only; the row actions stay in the panel.
- **Query cost.** One query for the latest event per punishment inside the window. The implementation checks its plan
  on `case_event`. If an index is needed, it is an append-only migration (the next free number on `origin/main` at that
  time, V42 when this is written), named in `tasks.md` and in that PR. None is assumed here.
- **Badges.** The navigation badges keep the four sections they count today (restructure decision 2). The new section
  does not add to a badge.

### Decision 2a: no change to the Actor or the wire format beyond the one section

`InboxApi.summary` gains the caller's `Actor` parameter, which the framework injects from the verified token; the HTTP
request is unchanged. The response gains `changedByOthers`; clients that do not know the field ignore it.

## Decision 3: no migration

No endpoint in this change writes, and the inbox reads existing tables. No `V*` file is added. If the index in
Decision 2 is needed, it is the only migration and it is appended, never an old one edited.

## Decision 4: client spec

`client/specs/blackhole-api-0.0.67.yml` is the newest published file. The implementation copies it to
`blackhole-api-0.0.68.yml` (or the next free number at that time), adds the seven paths and the `changedByOthers`
section, and moves the `inputSpec` pin in `client/build.gradle.kts`. No published file is edited. The change
`add-portal-bot-challenge` also plans a spec bump; whichever merges first takes the number, and the other one takes the
next.

## Known gaps (not fixed here, listed on purpose)

1. **No version on five of the entities.** Appeal, exemption review, area ban, allow-list entry and staff actor have no
   version column. Their responses carry no `version` and no `ETag`. The restructure's confirmed actions on them
   (decide an appeal, resolve an exemption review, lift an area ban, remove an allow-list entry, anonymize a staff
   actor) therefore stay confirmation-only, as restructure decision 8 allows. A follow-up change adds `version` to
   these tables with an append-only migration (V42 or later) and sends `If-Match` on the writes. `updatedAt` is not used
   as a version: it is not a version and is not a safe precondition.
2. **The staff actor read has no roles and no link status.** Restructure design 7.3 asked for both. The roles are stored
   (`StaffActorEntity.roles`) but are not in `StaffActorDTO.Item`, and adding them would change the list item too. The
   links tab uses the existing `GET /admin/staff-link` list and filters it in the panel. That body carries a raw
   Minecraft UUID (not in a URL). Changing this is a follow-up decision.
3. **List filters are missing.** `GET /appeal` takes no `status`, `GET /report` takes only `reportedHash` (no `status`),
   and `GET /admin/audit` takes no `surface`. Restructure phase 6 wants them as server-side query state. These are list
   parameters for a separate change.
4. **`AppealDTO` is not a sealed marker**, so its 404 has no error body, as the existing review endpoint has none.
5. **Window.** The changed-by-others section reads 24 hours, not "today" (see Decision 2). Open question 1.
6. **Discord user ids in paths.** Two existing GET routes take a Discord user id in the path (`DiscordStaffLinksApi`
   `/{discordUserId}` and `DiscordLinkApi` `/{discordUserId}`). The panel must not call them from an address. Not changed
   here.

## Open questions for the maintainer

1. Is a rolling 24 hours right for "changed by others", or does the team want "since the start of the local day"? The
   second needs a time zone parameter.
2. Should a staff actor's roles and link status join the read in this change (known gap 2), or wait for their own
   change?
