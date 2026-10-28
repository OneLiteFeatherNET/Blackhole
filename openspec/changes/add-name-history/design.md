## Context

The code this design builds on (checked on `main`, 2026-10-28):

- `backend/.../playerresolver/`: `GET /player/resolve/{name}` resolves a name through Otis, Mojang and NameMC
  (`openspec/specs/player-resolution`). It returns `uuid`, `name`, `source` and `owner`. Nothing is stored
  per name; the cache is keyed by the name and expires after `blackhole.player-resolver.cache.ttl`.
- The backend stores no player names. `IpCorrelationService` and `ClientFingerprintService` store owner hashes
  and tokens only.
- `web/src/views/PlayerDetailView.vue` is routed by owner hash (`validOwner` on the path parameter) and uses
  an in-memory lookup store (`lookup.nameOf(owner)`) for the title; nothing is written to browser storage.
- `web/src/views/PlayersView.vue` searches by name through `api.resolvePlayer` and then opens the detail for
  the resolved owner.
- Velocity's `PunishInfoCommand` guards `/blackhole <user> info` with `@Permission("blackhole.user.info")`.
- `add-player-intelligence` adds `player_sighting` with `owner_hash`, `player_name` and `started_at`, and its
  retention sweeper deletes sessions after `blackhole.intel.retention-days`.

## Goals / Non-Goals

**Goals**
- Every name an account logged in with, with first and last seen, for staff.
- Find an account by an old name without an external service.
- A name history that outlives the 90-day session records, so "first seen" stays meaningful.

**Non-Goals**
- Past names before the first session record.
- Notifications on name change.
- Any change to the live resolver chain or its response contract.

## Decisions

### 1. The history is its own table, written at session start

Table `player_name_history`, one row per (owner hash, lower-case name):

| Column | Type | Notes |
|---|---|---|
| `owner_hash` | char(128) | part of the primary key |
| `name_lower` | varchar(16) | part of the primary key; the case-insensitive match key |
| `name` | varchar(16) | the most recent casing seen |
| `first_seen`, `last_seen` | timestamp | |

The primary key is `(owner_hash, name_lower)`; a second index on `name_lower` serves the old-name lookup.
`SessionService.start` (from `add-player-intelligence`) upserts the row after the session is stored: insert
on first sight, otherwise update `last_seen` and `name`. The upsert is one statement, so two concurrent logins
of the same name cannot create two rows.

Rejected: deriving the history with `GROUP BY` over `player_sighting` on every read (the rows would expire
with the 90-day session retention, so "first seen" would silently become "first seen in the last 90 days");
a second copy written by a nightly job (up to a day of missing names, and the job would need its own
watermark).

### 2. Backfill comes from the session table at migration time

The migration `V<next>__player_name_history.sql` creates the table and runs

    INSERT INTO player_name_history (owner_hash, name_lower, name, first_seen, last_seen)
    SELECT owner_hash, lower(player_name), max(player_name), min(started_at), max(started_at)
    FROM player_sighting GROUP BY owner_hash, lower(player_name);

(the exact casing expression is chosen at implementation time for the target database). It is run once when
the migration runs, so every name still in `player_sighting` is kept. Names already removed by the session
sweep are not recoverable and are not reconstructed.

### 3. Name retention is longer than session retention

`blackhole.intel.name-retention-days` (default 180) with `blackhole.intel.name-retention-sweep-cron` (default
04:00 daily). The sweeper deletes history rows whose `last_seen` is older than the retention. The default is
longer than the 90-day session retention because the point of the history is to connect names across a longer
time; it is shorter than a year to keep the personal data small. Open question 1 asks the maintainer to confirm.

Rejected: the session retention for names too (it would make "first seen" misleading, decision 1); no retention
(personal data without a limit, which the GDPR principle rules out).

### 4. Erasure removes names with the owner

Erasure on request is kept by the amended data-protection principle. Whatever erasure procedure exists when this
change is implemented also deletes the owner's `player_name_history` rows alongside its sessions and sightings.
If no erasure procedure exists yet, that is a prerequisite gap to raise in `add-player-intelligence`, not a
reason to skip the rows here.

### 5. Routes: two reads, under the intel prefix

- `GET /intel/players/{owner}/names`: the names of one owner, newest first, paginated. VIEWER and above, and
  PROXY, because the command runs on the proxy on behalf of staff.
- `GET /intel/names/{name}/owners`: the owners that used a name (case-insensitive), at most 20, newest first.
  Each entry has the owner hash, the newest name of that owner and first and last seen for the matched name.
  VIEWER and above, and PROXY.

The panel uses the second route when the live lookup returns 404. It then opens the player page with the owner
hash, which the detail page already accepts as its route key. The sealed DTOs are `NameHistoryDTO` (`Response`,
`OwnerResponse`, `Error`); the controller stays thin and the service answers `Error` instead of throwing.

Rejected: changing `GET /player/resolve/{name}` to answer old names. Its `uuid` field is non-null in the generated
client; old names have no UUID here (the owner hash is the only identifier stored), so the change would either
break the client or return a fake UUID.

### 6. The command reuses the existing lookup and permission

`/blackhole <user> names` resolves the target in this order: an online player on this proxy (owner from the
hash), then `GET /player/resolve/{name}`, then `GET /intel/names/{name}/owners` for an old name. It is guarded
with `blackhole.user.info`, the permission that already covers the player's information. It prints up to 20
names, newest first, with the relative and absolute last-seen time. Messages are added to both English and
German property files.

Rejected: a new permission `blackhole.user.names` (staff would need a second grant for information they already
see in `info`).

### 7. Old-name matching is case-insensitive and exact

Minecraft names are case-insensitive, so `name_lower` is the match key. There is no prefix or fuzzy match; a
partial name finds nothing. This keeps the lookup predictable for staff and avoids a list of unrelated players.

## Risks / Trade-offs

- **Coverage starts at go-live.** Accounts that have not logged in since the session records began have no
  history. The panel says the history starts at the first recorded login; the sessions section of the player
  page shows the same date.
- **Name impersonation** is visible in the history but nothing alerts on it. A later change can add an alert
  on a name that a banned account used.
- **Write cost**: one upsert per login on top of the session insert. Negligible at the expected login rate.

## Migration plan

1. Merge `add-player-intelligence` (it creates `player_sighting`).
2. Run the migration: the table, the index, and the backfill from the sightings that exist at that point.
3. Ship the backend routes and the sweeper, then the Velocity command and the panel.
4. Rollback: remove the routes and the sweeper; the table can stay, unused.

## Open questions

1. Name retention: 180 days (proposed) or the same 90 days as sessions?
2. Should the command show the names of banned accounts only to moderators and above, or to everyone with
   `blackhole.user.info`?
3. Should the panel show a name-change badge on the player page when the current name differs from a name
   used earlier?
