## Why

When a banned player comes back on a fresh account, staff want to see the other accounts that have been
on the same connection or the same client, and whether those accounts are banned too. Today that means
opening the panel and reading the "Possible alt accounts" hints, which only exist for a player page.
Moderators who are in game, or who are handling a join right now, have no way to ask. The data is already
there once `add-player-intelligence` records sessions with the clear IP, and the names are there once
`add-name-history` is in place.

## What Changes

- **New in-game command `/alts <player>`** (permission `blackhole.alts`). It lists the accounts that share an
  address or a client fingerprint with the target, each with its name, the reason it is linked, its last seen
  time and its active punishments (or "none"). The list has at most 20 entries, newest first.
- **Clickable entries.** Each entry is clickable. A click puts `/blackhole <name> info` into the chat box for
  the staff member to send; it does not run the command.
- **Old names work.** The target may be a name the account no longer uses; the lookup falls back to the old-name
  route of `add-name-history`.
- **Backend endpoint `GET /evasion/{owner}/alts`.** It answers the list for one owner from the sessions, the
  legacy sightings and the client fingerprint links, with the active punishment sections of each linked account.
  It is built on the existing evasion correlation and does not change it.
- **Panel**: the "Possible alt accounts" panel keeps its current data source. This change adds no panel code.
- **No IP or owner hash in game.** The command shows reasons in words ("shared address", "shared client
  fingerprint"), never an address or a hash.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `evasion-detection`: a read of linked accounts for one owner, with the reasons and the active punishment
  sections of each linked account.
- `proxy-enforcement`: the `/alts` command, its permission, its target resolution and its clickable output.

## Prerequisites

- `add-player-intelligence` (sessions with the clear IP) and `add-name-history` (old-name fallback) must be merged
  first. Without them the command still works for online players and current names, but shared addresses come
  only from the legacy sightings and old names are not found.
- `add-client-fingerprint-rules` for fingerprint links to have data; without it the fingerprint reason never appears.
- `amend-data-protection-principle` before the code PR.

## Impact

- **Backend**: a new `AltLookupService` in the `evasion/` package, a route on `EvasionApi`, a sealed DTO
  (`AltsDTO` with `Response`, `Linked`, `Error`).
- **Database**: none. No migration.
- **API**: one new route. `client/specs/blackhole-api-*.yml` gets the next free version (after `0.0.44` at
  implementation time). Additive, so not BREAKING.
- **Velocity**: `AltsCommand`, its messages in the English and German property files, the async call with a timeout.
- **Web**: none.

## Out of scope

- Console use of `/alts` (the other lookup commands also require a player sender; this one follows them).
- Acting on the list: no bulk ban, no alias of accounts, no automatic action on linked accounts.
- Changing the evasion detection window or its signal.
- An alt lookup in the panel from the new route. The existing panel hints stay on `/evasion/{owner}/sightings`;
  open question 1 asks whether they should move.
- Showing IPs, owner hashes or the raw UUID anywhere in game.

Ships as: feat(velocity): add the in-game alt lookup command
