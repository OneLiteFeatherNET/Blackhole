## Context

The code this design builds on (checked on `main`, 2026-10-28):

- Revoke (`PunishmentApplicationService.revoke`, lines ~518-560): clears the slot, sets `lifted`, publishes
  `punishment.revoked`. A **permanent** punishment that is revoked resets the section score to the pardon score
  (`EloScoring.resetToPardon`). A **temporary** revoke does not refund its ELO cost (javadoc, line 512).
- Appeal decision (`AppealDecisionService.applyDecision`, lines ~210-253): a `GRANTED_DURATION_REDUCTION` writes the
  new expiry into the metadata and sets `permanent = false`; if the punishment was permanent it calls
  `resetToPardon`. A full lift refunds a temporary cost (`restoreElo`, `APPEAL_REFUND`) and resets a permanent one.
  Both use `punishmentRepository.update` and `profileCache.evict`.
- Re-templating (spec `punishment-management` "Re-templating is idempotent and announced"): an active punishment
  publishes `punishment.updated`; `PunishmentSyncHandler` routes `punishment.updated` to the mirror `set`, and the
  Velocity `NatsSyncService` handles `PUNISHMENT_UPDATED` with the same path as `created`. An amendment reuses this.
- `PunishmentEntity` keeps `permanent`, `lifted`, `eloCost`, `escalationGroup`, `stepIndex`, `stepCount`, `source`,
  `actorOid`, `recordedByOid` and a metadata map with `Expirable.META_DATA_KEY_EXPIRATION_DATE`, `reason`, `automatic`
  and the ban's Ban ID (`BanIdService`). No `version` column exists.
- The appeal workflow: `Severe punishments cannot be fully lifted` (a permanent network ban is SEVERE and refused a full
  lift) and `Minimum wait period` (measured from the punishment's creation, `min-days-auto` or `min-days-manual`).
- `standardize-api-pagination-and-problems` (in flight, not merged): `ETag: "<version>"` on reads of versioned resources,
  `If-Match` on updates, 412 `PRECONDITION_FAILED`, 428 `PRECONDITION_REQUIRED`, `Idempotency-Key` on creating POSTs
  (D17, D18), the cursor envelope (`api-pagination`), `Link` headers (D15). Its D17 table lists the resources; the
  punishment is not on it yet.
- `openspec/config.yaml` principles: principle 4 (dual-ELO stays automatic; every punishment contestable), principle 6
  (append-only migrations). A staff amendment changes a punishment that was issued by staff or by the ELO system; the
  appeal rules keep applying to it.
- `.claude/rules/appeal.md`: `min-days-auto` is intentionally shorter than `min-days-manual`.

## Goals / Non-Goals

**Goals**: correct an active punishment in place with a reason; keep one record of what changed, who did it and why;
keep the ELO meaning of a punishment unchanged except where the existing pardon rule already applies; make the ban
screen and the appeal review show the current state.

**Non-Goals**: lifting by amendment; a second punishment for a correction; recalculating past ELO; amendments of
warnings or kicks; amending expired or lifted punishments.

## Decisions

### 1. Changes and their shape

One request is one amendment with one or more of these changes (at least one, no empty amendments, 422):

| Change | Meaning | Who |
|---|---|---|
| `expiresAt` (RFC 3339, UTC) | new expiry, must be after now and after the current expiry for an extension; before it for a shortening | shorten: MODERATOR (temporary only); extend: ADMIN |
| `permanent: true` | make permanent | ADMIN |
| `permanent: false` + `expiresAt` | make a permanent punishment temporary (expiry required, in the future) | ADMIN |
| `reason` (1 to 200 characters, `PiiGuard`) | the reason text shown to the player and the proxy | MODERATOR |
| `templateKey` | the punishment's template reference (see decision 4) | MODERATOR |

`justification` is required in every request: 10 to 500 characters, `PiiGuard` (no UUID, IP or Ban ID), free text,
stored with the amendment and shown on the case. Shortening to "now" or to a past instant is refused (422
`AMENDMENT_INVALID`): ending a punishment is a revoke, so that it keeps its ELO and evidence paths.

Extending a temporary punishment and making it permanent are ADMIN only. The brief's recommendation is
MODERATOR may shorten, ADMIN may extend or make permanent; this design takes it and adds one rule: making a
permanent punishment temporary is ADMIN only too, because it resets the score (decision 5). See open question 2.

### 2. Storage and concurrency

- `punishments.version BIGINT NOT NULL DEFAULT 0`. Every amendment and every other write that changes the row
  (revoke, lift, expiry sweep, template link) increments it, so `ETag` changes whenever the punishment changes. The
  increment is part of the same `update` call that writes the row, so no separate version write exists.
- `GET /punishment/{identifier}` returns the punishment with `ETag: "<version>"`. It is new; it is the read that
  `If-Match` refers to. The `ETag` rule of `standardize-api-pagination-and-problems` (D17) is extended with
  `punishment`; that change's table is updated when it is archived.
- `POST /punishment/{identifier}/amendments` requires `If-Match` (412 `PRECONDITION_FAILED` with the current `ETag` when
  stale; 428 `PRECONDITION_REQUIRED` when missing or `*`). The check and the write compare the version in the same
  conditional `UPDATE ... WHERE version = :expected`. No race between two moderators: one gets 412.

### 3. Amendment record

Table `punishment_amendment`: `id UUID PK`, `punishment_identifier` (the Ban ID's punishment identifier, FK to
`punishments`), `actor_oid UUID` (the token's `oid`, or the proxy's `onBehalfOf` per decision 9), `justification
VARCHAR(500)`, `before_state` and `after_state` as JSON (`expiresAt`, `permanent`, `reason`, `templateKey`, `version`),
`version_after BIGINT`, `created_at BIGINT`. Append-only. No owner hash, no IP, no UUID in the table beyond the actor
`oid`, which is the staff member's and is already stored on punishments as `actor_oid`.

Routes (`punishment-amendment` capability): `POST` above (201, body `AmendmentDTO.Response` with `before`, `after`,
`version`, and `ETag` of the new version); `GET /punishment/{identifier}/amendments` (cursor envelope, newest first,
`Link` per D15).

Errors: 400 `VALIDATION` (justification, reason, malformed time, `PiiGuard`); 403 when the caller's role is below the
change's threshold (decision 1), with the problem code `FORBIDDEN`; 404 `NOT_FOUND` (unknown identifier); 409 `CONFLICT`
when the punishment is not active (expired, lifted, or a kick or warning); 412 and 428 (decision 2); 422
`AMENDMENT_INVALID` (empty amendment, expiry in the past, shortening that is not shorter, extension that is not later);
422 `AMENDMENT_TEMPLATE_UNKNOWN` for a template key that does not exist.

Idempotency: `Idempotency-Key` on the POST (`standardize-api-pagination-and-problems` D18): a repeat with the same key
and body returns the original 201 and writes nothing. A repeat with the same key and a different body is 422
`IDEMPOTENCY_KEY_INVALID`.

### 4. Template reference

`templateKey` changes the template reference of the punishment (the `templateIdentifier` in the mirror payload and
the reason text, if the reason was not overridden). The **escalation group, step index and step count stay as they
were recorded at apply time**, so changing the template never changes how many earlier offences the player has or
which step the next application selects. The reason is taken from the new template when no explicit `reason` is sent
in the same amendment; an explicit `reason` wins.

### 5. ELO

- Amendments change no ELO score and write no ELO event, with one exception.
- **Exception**: a permanent punishment made temporary (`permanent: false`) resets the section score to the pardon
  score through `EloScoring.resetToPardon` with reason `PARDON_RESET`, with the same metadata as the appeal's duration
  reduction (`AppealDecisionService.applyDecision`, line 247). Reason: the score of a permanent punishment is the
  section's floor; a player who is no longer permanently punished must not stay at that floor. This mirrors the
  appeal path exactly, so the two paths agree.
- A shortening of a temporary punishment is **not** refunded, consistent with revoke, which does not refund temporary
  costs. Only a granted appeal refunds (`restoreElo`). Refunding a shortened punishment would make "shorten" a
  cheaper way to appeal than the appeal workflow; this design says no.
- Extension, making permanent, and reason or template changes change no score.

### 6. Events and the case

- After the write, the backend publishes `punishment.updated` with the full punishment state in the same shape as
  `punishment.created` (`PunishmentEvents.created`), so the proxy's mirror `set` replaces the entry. A failed publish
  does not fail the amendment (as for every domain event); the mirror catches up on the next `punishment.updated` or
  expiry.
- Case timeline event `punishment.amended` (`CaseTimeline.amended(...)`), payload `Amended(before, after,
  justification)` with the two states, dedupe key `amended:<amendmentId>`, recorded after the write. A recording failure
  never fails the amendment (as for `punishment.retemplated`). The case shows who amended, when, and both states.
- The evidence (`PunishmentEvidenceGateway`) is not changed: evidence stays attached.

### 7. Appeals

- An amendment is allowed while an appeal is open on the punishment. The appeal review reads the punishment's current
  state and lists the amendment history on the appeal page (case event shows on the case).
- A decision applies to the punishment as it stands at decision time. `AppealDecisionService.applyDecision` already reads
  the active entity, so it sees the amended expiry; no branch is added.
- The minimum wait is measured from `createdAt` of the punishment, not from the amendment: an amendment does not restart
  it. Stated in a scenario, so it is tested.
- The severity tier is read at review time from the current state. A permanent ban made temporary by amendment is no
  longer SEVERE and can be fully lifted at review; a temporary ban made permanent becomes SEVERE and a full lift is
  refused (400), as the existing rule says. The checklist snapshot records the tier at submission; the review records the
  tier it used.
- An amendment never changes the appeal status. A granted full lift after an amendment still lifts the punishment.

### 8. Ban screen and online players

- Velocity handles `punishment.updated` through its existing mirror `set` path. The ban screen reads the expiry and
  reason from the mirror on the next login, so a shortened or extended ban shows the new expiry at the next attempt.
- A player online under a **network** ban (the ban is enforced on connect, so this only happens if a ban was placed
  while they were connected) is disconnected with the updated screen when the amendment makes it permanent or extends
  it. A shortening does not disconnect anyone. A mute amendment changes the chat gate on the next message.
- The re-enforcement reuses the existing path for a new ban on an online player (`punishment.created`). No new
  enforcement code; the handler is called with the amended payload.
- A server punishment (`SERVER` enforcement) is handled the same way on its current server only.

### 9. In-game amendment and the actor

- `/blackhole <user> amend ...` is sent by the proxy with the proxy's token and the staff member's `onBehalfOf` (the same
  path as `/blackhole ... ban` today). The backend records the `onBehalfOf` oid as `actor_oid` and as the amendment
  actor, through `Actor.attribution(onBehalfOf)`.
- The role check for an in-game amendment needs the staff member's role, which the proxy does not hold. This is open
  question 1. The default in this design is option (b): an in-game amendment that is ADMIN-only under decision 1 is
  refused with 403 and the panel does it; MODERATOR changes work in game. The role is then never resolved from the
  proxy's word alone.

### 10. Migration

`V<next>__punishment_amendment.sql`, the next free version at implementation (append-only):

- `ALTER TABLE punishments ADD COLUMN version BIGINT NOT NULL DEFAULT 0`;
- `CREATE TABLE punishment_amendment (...)` as in decision 3, with FK to `punishments(identifier)` and an index on
  `(punishment_identifier, created_at)`.

Existing rows keep version 0; no row is rewritten. Revoke and expiry sweeps start incrementing the version in the same
change, so an `ETag` read before a revoke is stale after it.

### 11. Client and compatibility

The client spec adds the three routes, `version` on the punishment schema and the amendment schemas, in the next free
`client/specs/blackhole-api-*.yml` version. This change is not BREAKING for existing routes: new fields are additive and
an older client ignores them. The amend command needs the new client, so the Velocity PR depends on it.

### 12. Tests (F.I.R.S.T.)

- Backend unit (no DB, fixed `Clock`): the change rules (shorten only temporary and later, extend only later, make
  permanent, make temporary needs an expiry, empty amendment refused, past expiry refused); the role rules per change
  (MODERATOR shorten and reason, ADMIN the rest); the justification length and `PiiGuard`; the template change rule (the
  escalation group is kept).
- Backend integration (Postgres, fixed `Clock`): `If-Match` stale gives 412 and the current `ETag`; missing gives 428; two
  concurrent amendments with the same version give one 201 and one 412; a repeat `Idempotency-Key` writes nothing; a
  permanent punishment made temporary resets the score once (pardon reset); a temporary shortening changes no score; the
  case has one `punishment.amended` with both states; an amendment never changes escalation counts.
- Appeal: a full lift after an amendment still lifts; the minimum wait is not restarted; a temporary ban made permanent
  refuses a full lift (400).
- Events: the `punishment.updated` payload has no reason change beyond the amended reason and no IP or raw UUID
  (`AmendmentEventsNoLeakTest`).
- Velocity unit (no server): the amend command parses the duration and the template and calls the gateway with the
  `If-Match` read first; a 412 shows the "changed by someone else" line; the re-enforcement decision for an online player
  (permanent or extended: disconnect; shortened: no disconnect).
- Web component tests (Vitest, fake timers, no network): the dialog shows before and after; submit is disabled without a
  justification of 10 characters; the ADMIN-only options are not shown for MODERATOR; a 412 shows the conflict message.
- Every backend and Velocity task group lands its own JUnit tests; every web group its own component tests.

## Risks / Trade-offs

- **Amendments can be used to hide a lenient decision.** Accepted: every amendment is on the case with both states, and
  the justification is mandatory. The ADMIN rule on extension and permanence keeps the strict direction out of
  moderator hands.
- **A moderator can shorten a ban by a lot.** Accepted for temporary punishments, with the case record. The ADMIN rule is
  the control for sensitive cases; a later review can lift the rule (open question 2).
- **Pardon reset on make-temporary is a score increase for the player.** It mirrors the appeal path exactly; the ADMIN-only
  rule and the case record are the controls.
- **Actor role for in-game amendments is not established by the proxy.** Open question 1; until answered, the
  staff-link check applies and ADMIN-only changes can be refused in game and done in the panel.
- **The version column makes every punishment write touch the row version.** Accepted: the increment is in the same
  `update`; no extra query.

## Open questions

1. **In-game role check.** The proxy relays `onBehalfOf` but not the staff role. Options: (a) resolve the role through
   `StaffLinkService` on the backend (this design's default); (b) forbid ADMIN-only changes in game and allow only
   MODERATOR changes there; (c) the proxy looks up the role itself. Recommendation: (b) for the first release, because it
   needs no new trust path; the panel covers the rest.
2. **Permission matrix.** This design: MODERATOR shortens temporary punishments and changes reason and template; ADMIN
   extends, makes permanent and makes temporary. Confirm before the code PR, in particular whether a MODERATOR may shorten
   a ban by any amount.
3. **Refund on shortening.** This design says no refund (consistent with revoke). Confirm.
4. **Idempotency of `version` on revoke.** Confirm that revoke and the expiry sweep increment `version` (decision 10), so a
   stale amendment after a revoke is refused with 412 rather than 409.
