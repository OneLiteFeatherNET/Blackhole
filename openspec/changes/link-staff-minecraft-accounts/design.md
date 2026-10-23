# Design

## Context

Observed state (after `add-team-sso`): `ActorResolver` turns the validated token into
`Actor(oid, displayName, kind, proxy)`; `Actor.attribution(onBehalfOf)` yields
`Attribution(source, recordedByOid)` where `source` is the in-game UUID the proxy vouches for and
`recordedByOid` is the proxy's `oid`. `staff_actor` (V9) holds `oid`, kind, display name and
timestamps. `AppealDecisionService` rejects a review when the reviewer's `oid` equals the
punishment's `source` or `recorded_by_oid`. `StaffActorService.anonymize` clears the display
name. Roles: `ADMIN` > `MODERATOR` > `VIEWER`, `PROXY` and `METRICS` imply nothing.
Flyway is at V9; `staff-temp-bans-elo-escalation` may add one first, so the number below is
confirmed at apply time.

Storage check (done against the code): staff identifiers are stored **raw**. The attribution
columns (`punishments.source`, `reports.resolved_by`, `appeals.decided_by`, evidence
`created_by`) are plain `uuid` columns and `recorded_by_oid` is a raw `oid`. Only player owners
(`punishment_profiles.owner`, `elo_profiles.owner`) are tokenized. So the Minecraft UUID of a
staff member is already stored raw in every record that member issued, and the link table
storing it raw adds no new class of exposure. Hashing it would also break the join the link
exists for. The link is staff personal data like `staff_actor` (see decision 10).

## Goals / Non-Goals

Goals: both sides proven; in-game actions attributed to the real person; self-review covers
in-game bans; no new auth system; nothing secret in logs or events.
Non-goals: see "Out of scope" in proposal.md.

## Decisions

### 1. Code format, lifetime, guessing resistance

- 8 characters of Crockford base32 (`0-9 A-Z` without `I L O U`), 40 bits from `SecureRandom`,
  shown as `ABCD-EFGH`. Input is case-insensitive, the dash optional, and `I`/`L` read as `1`,
  `O` as `0` (Crockford's rule), so typos between look-alikes still work.
- Valid 5 minutes (`BLACKHOLE_STAFF_LINK_TTL`, default `PT5M`), single use, checked against the
  injected `Clock`.
- Stored only as a hash (SHA-256 hex, via the existing `SecretHasher` if it fits). The plain code
  exists only in the issue response. It is never logged, never in an event, never in an error.
- One live code per `oid`: issuing a new code replaces the previous row, so the old one stops
  working.
- Guessing resistance, without an app-level rate limiter (principle 5): (a) entropy: a guess
  hits one of the few live codes with probability about `live/2^40`; (b) the 5 minute TTL;
  (c) the code is bound to the issuing `oid`, so a guessed code can only ever link the guesser's
  own Minecraft account to *that staff member's* identity, and the guesser needs `blackhole.link`
  (a staff permission) plus the proxy call carrying their own UUID; (d) a **per-code
  failed-attempt cap**: every failed confirm increments `failed_attempts` on all live codes, and
  a code reaching `BLACKHOLE_STAFF_LINK_MAX_FAILED_ATTEMPTS` (default 10) is burned. Total
  guesses against any code are therefore bounded to 10, i.e. success odds of roughly
  `10 * live / 2^40` per code lifetime, independent of what infra does.
- The cap is state on the code rows, not a rate limiter: no time window, no per-caller counting,
  no throttling of requests. A failed confirm cannot be attributed to a single row (an unknown
  code identifies none), which is why it counts against all live codes. Trade-off: a staff member
  with `blackhole.link` can burn others' codes by typing wrong ones; they are trusted staff, the
  attempt is logged with the `onBehalfOf` UUID and the owner just issues a new code. This is the
  one point we want the user to confirm (open question 1).
- Responses for wrong, expired, reused and burned codes are identical (one `INVALID_CODE` error)
  so a prober learns nothing about which codes exist.

### 2. Cardinality, re-link, unlink

One Minecraft UUID per `oid` and one `oid` per UUID: `staff_link.oid` is the primary key and
`minecraft_uuid` is `UNIQUE`. Confirming when the `oid` already has another UUID, or the UUID
already belongs to another `oid`, **replaces** the old link in one service call (delete old, insert
new); each removed link publishes `staff-link.removed` with reason `REPLACED`, so it is audited.
The one-to-one rule is the database's, so a race fails on the constraint and is reported as
`CONFLICT`, never silently produces two links.

Unlink: the staff member for themselves (`DELETE /staff-link/me`), an admin for anyone
(`DELETE /admin/staff-link/{oid}`), and `POST /admin/staff-actor/{oid}/anonymize` also removes
the link (a leaving person must not stay attributable by UUID). Unlinking never rewrites
history: stored `actor_oid` values stay.

### 3. Storage and migration

Append-only `V10__staff_account_link.sql` (number confirmed at apply time; never edit V1-V9):

```
staff_link_code(oid uuid PK, code_hash varchar(64) NOT NULL UNIQUE,
                expires_at bigint NOT NULL, failed_attempts int NOT NULL DEFAULT 0)
staff_link(oid uuid PK, minecraft_uuid uuid NOT NULL UNIQUE,
           linked_at bigint NOT NULL, linked_by_oid uuid NOT NULL)
ALTER TABLE punishments ADD COLUMN actor_oid uuid;      -- same for reports,
ALTER TABLE evidence_items ADD COLUMN actor_oid uuid;   -- evidence_items and
ALTER TABLE evidence_audit ADD COLUMN actor_oid uuid;   -- evidence_audit
```

No foreign keys (same rule as V9: anonymizing or pruning never blocks history). Appeals get no
`actor_oid`: reviews are web-only, so `decided_by` already is the `oid`. Existing rows keep NULL.
Expired and burned code rows are deleted lazily on the next issue or confirm (no scheduler, no
sleeps).

### 4. Attribution

When the proxy sends `onBehalfOf`, `Actor.attribution` is unchanged (`source` = the in-game UUID,
`recorded_by_oid` = the proxy). The service layer additionally looks up the link of
`onBehalfOf` and writes the linked `oid` as `actor_oid`; no link means NULL. `source` is never
rewritten, so the unverified-vs-linked distinction stays visible. Punishment, report and
evidence responses expose `actorOid` and `actorName` (from `staff_actor`, null once anonymized).
For a web caller `actor_oid` stays NULL because `recorded_by_oid` already is the person.

### 5. Self-review

`AppealDecisionService` rejects (403) when the reviewer's `oid` equals the punishment's `source`,
`recorded_by_oid` or `actor_oid`, **or** when the punishment's `source` is currently linked to
the reviewer's `oid`. The last clause covers an in-game ban issued before the link existed
without rewriting history (see 6). Known limit: unlink-then-review evades the live-link clause
for pre-link bans only; bans issued after linking keep the stored `actor_oid` and stay covered.

### 6. No backfill

Creating a link does not rewrite earlier punishments. Reasons: the Minecraft UUID was only proven
to belong to this person *now*, not when the old rows were written (accounts get handed over,
and a re-link would retroactively reassign history); history stays append-only and auditable
("what did we know at the time"); a bulk UPDATE buys nothing the live-link clause in decision 5
does not already give for self-review. The timeline for old rows shows the UUID; an opt-in tool
can be its own change.

### 7. API

Sealed `StaffLinkDTO` marker (`CreateRequest`, `Response`, `Error`; `Error` implements
`ErrorResponse`), OpenAPI on `StaffLinkApi`/`AdminStaffLinkApi`, thin controllers, one service
call each, `@Secured` per route, API version 1.

| Route | Role | Notes |
|---|---|---|
| `POST /staff-link/code` | `VIEWER` (any staff role), human token only | returns `{ code, expiresAt }`; service principals get 403 |
| `GET /staff-link/me` | `VIEWER` | `{ linked, minecraftUuid?, linkedAt? }`, never the code |
| `DELETE /staff-link/me` | `VIEWER` | unlinks the caller |
| `POST /staff-link/confirm` | `PROXY` | body `{ code, onBehalfOf }`; `onBehalfOf` required |
| `GET /staff-link/by-minecraft/{uuid}` | `PROXY` | for `/blackhole whoami`: `{ linked, displayName? }` |
| `GET /admin/staff-link` | `ADMIN` | list of links with display names |
| `DELETE /admin/staff-link/{oid}` | `ADMIN` | unlink anyone |

The matrix in `add-team-sso/design.md` decision 3 gets these rows and
`PermissionMatrixIntegrationTest` is extended. New client spec `blackhole-api-0.0.16.yml`.
Error kinds: `INVALID_CODE` (400), `CONFLICT` (409), `NOT_FOUND` (404), `FORBIDDEN` (403).

### 8. Velocity

- `/blackhole link <code>`, permission `blackhole.link`, players only (the executing UUID is the
  proof; the console has none). It calls `POST /staff-link/confirm` with `onBehalfOf` = the
  executing player's UUID.
- `/blackhole whoami` (permission `blackhole.link`) shows the link status via
  `GET /staff-link/by-minecraft/{uuid}`.
- Messages in the existing `bh:` style, English and German, same prefix and glyphs as
  `staff-command-messages`: `staff.link.success` (names the linked person), `staff.link.invalid`
  ("invalid or expired code", next step: issue a new one in the panel), `staff.link.already`
  (already linked, to whom, how to relink), `staff.link.usage`, `staff.link.playerOnly`,
  `staff.whoami.linked`, `staff.whoami.unlinked`.
- Backend errors go through the existing `CommandErrorHandlers`; the code is excluded from any
  logged command line.

### 9. Web

An account dialog opened from the top bar (next to "signed in as"): status line, "Minecraft-Konto
verknüpfen" / "Link Minecraft account" button, the code in large monospace text with a
countdown (`mm:ss`, driven by the server `expiresAt` and an injectable clock), a copy button, the
command to type (`/blackhole link ABCD-EFGH`), and an unlink button with a confirmation dialog.
After expiry the code is cleared and the button offers a new one. Accessibility: a real `dialog`
with a focus trap and focus return, labelled controls, the countdown not announced every second
(`aria-live` off; "code expired" announced once), copy result announced politely, no colour-only
state, works at 200% zoom. Strings in `de.json` and `en.json` under `account.link.*`. An ADMIN-only
table of links (display name, linked-at, unlink) and `actorName` on the punishment timeline. The
UI never decodes tokens and takes the status from the API.

### 10. Events, logs, GDPR

`staff-link.created` (`oid`, `linkedBy`) and `staff-link.removed` (`oid`, `removedBy`, `reason`
= `SELF`/`ADMIN`/`REPLACED`/`ANONYMIZED`) on the existing `blackhole.events` exchange. Logs carry
the `oid`, never the code or its hash. The link (`oid` and Minecraft UUID) is staff personal
data: purpose is accountability of moderation decisions; stored: the pair and timestamps only;
retention as long as the person is staff, ended by unlinking or anonymizing; history records
keep their `actor_oid`. The staff-data retention statement is still pending legal confirmation;
this change adds the link to it and the README keeps the "pending" marker.

### 11. Tests (F.I.R.S.T.)

Unit tests for the generator (alphabet, length, normalisation, hash-only). Service tests with an
in-memory repository fake and an injected `Clock` for TTL: issue, replace, confirm, expired,
reused, wrong, burned, one-to-one, unlink, anonymize, attribution, self-review. Controller and
matrix tests with self-signed JWTs (`TestJwtIssuer`), no Entra, no network. No `Thread.sleep`;
time moves by advancing the test clock. Velocity tests use a fake client; web tests a fake clock
and API. Each test builds its own fixture.

## Risks / Trade-offs

- A linked person who loses their Minecraft account can re-link with a new code; the old link is
  replaced and audited.
- The all-live-codes failure counter lets a staff member burn colleagues' codes (decision 1).
- Pre-link bans are only covered by the live-link clause, not by a stored `actor_oid`.
- Raw staff UUID in `staff_link`: consistent with today's storage, covered by the pending
  retention statement.

## Open Questions

1. Keep the failed-attempt counter on all live codes (bounded guesses, but staff can burn each
   other's codes), or drop the cap and rely on entropy, TTL and infra alone?
2. Legal confirmation of the staff-data retention statement (shared with `add-team-sso`).
