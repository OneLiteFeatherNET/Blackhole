## Why

A player who changes name keeps the same account, and their punishments, alts and history stay with
the account. Staff, however, search and talk by name. Today the backend stores no player name at all:
the lookup resolves a name live through Otis, Mojang or NameMC, which only knows the current name. So a
moderator who hears "he was called Notch before" or sees an old name in chat has no way to find the
account, and nothing shows that two names belong to one account. The names a UUID used are what
connects those names, and `add-player-intelligence` already records the name each login used.

## What Changes

- **Name history per account.** Every name a UUID logged in with is kept, with its first and last seen
  time. The history is built from the session records of `add-player-intelligence`, so it starts with
  that change and has no past names before it. Names are compared without case; the newest casing is shown.
- **Old-name lookup.** A name that no longer resolves through Mojang can be found by any name the account
  used. The backend answers with the owner hash, the raw UUID and the names the account used.
- **Panel.** The player page shows a "Name history" section. The player search tries the live lookup
  first and then the name history, and opens the same player page.
- **In game.** `/blackhole <user> names` lists the names of a player, newest first, with first and last
  seen. `<user>` may be an old name. The command needs the same permission as `/blackhole <user> info`.
- **Retention.** Name history has its own retention (`blackhole.intel.name-retention-days`, default 180),
  longer than the session data by design, and is removed by a sweeper. Erasure removes it too.
- **Not published.** Name history is staff-only. It never goes into the public ban list or to events for
  other systems.

No existing route or contract changes. The live lookup (`GET /player/resolve/{name}`) and its generated
client type stay as they are.

## Capabilities

### New Capabilities

- `player-name-history`: the name history of an owner hash, the old-name lookup, the retention of names and
  the route used by the panel and by the proxy command.

### Modified Capabilities

- `proxy-enforcement`: the `/blackhole <user> names` command and its permission.
- `admin-web-ui`: the name history section on the player page; the player search falls back to the old-name
  lookup when the live lookup finds nothing.

## Prerequisite

- `add-player-intelligence` must be merged first. The history is derived from the names in its session records.
  This change ships its own backfill from those records, so it adds no data of its own.
- `amend-data-protection-principle` must be merged before the code PR (see `add-player-intelligence`).

## Impact

- **Backend**: a new `intel/names/` feature package (service, repository, sweeper, DTOs, controller on `IntelApi`).
- **Database**: one new Flyway migration `V<next>__player_name_history.sql` that creates the table and fills it
  from `player_sighting` in one `INSERT ... SELECT`. Additive only.
- **API**: `GET /intel/players/{owner}/names` and `GET /intel/names/{name}/owners`. `client/specs/blackhole-api-*.yml`
  gets the next free version (after `0.0.44` at implementation time).
- **Velocity**: one subcommand and its messages (English and German properties).
- **Web**: the name history section, the search fallback, unit tests.
- **Config**: `blackhole.intel.name-retention-days` and `blackhole.intel.name-retention-sweep-cron`.

## Out of scope

- Past names from before the first session record. Mojang's name-history endpoint is not reliable enough to
  build on, so no external source is used for past names.
- A name-change alert or any notification when an account changes name.
- Changing `/player/resolve`. Its `uuid` field stays non-null; old names are answered by the new route.
- Publishing raw UUIDs, names or IPs anywhere public. Staff views show the UUID and names; nothing public does.
- Alt lookup, which reads these names in `add-alt-lookup-command`.

Ships as: feat(profile): add name history from player sessions and old-name lookup
