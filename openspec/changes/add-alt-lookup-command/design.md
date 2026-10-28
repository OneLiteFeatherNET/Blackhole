## Context

The code this design builds on (checked on `main`, 2026-10-28):

- `backend/.../evasion/controller/EvasionController.java`: `@Version(V1) @Controller("/evasion")`, `POST /evasion/record`
  and `GET /evasion/{owner}/sightings`. The sightings answer lists the other owners that shared a hashed address in
  the detection window, with reason `SHARED_IP`, and never an address or token (`openspec/specs/evasion-detection`).
- `backend/.../evasion/service/ClientFingerprintService.java`: `linksOf(owner)` returns `Link(owner, sharedFingerprints,
  firstSeen, lastSeen)` for owners that share a fingerprint inside its window. It returns an empty list when fingerprints
  are disabled.
- `backend/.../playerresolver/`: `GET /player/resolve/{name}` returns the owner hash of a current name.
- Velocity commands use Cloud annotations with `requiredSender = Player.class` and `@Permission(...)`, for example
  `PunishInfoCommand` (`blackhole.user.info`, `blackhole.ban.history`).
- `add-player-intelligence` adds sessions with the clear IP (`player_sighting.ip`) and the keyed token
  (`player_sighting.ip_token`). `add-name-history` adds the names of an owner and the old-name route.
- The panel's "Possible alt accounts" panel reads `GET /evasion/{owner}/sightings` (`openspec/specs/admin-web-ui`,
  "Evasion hints on the player page").

## Goals / Non-Goals

**Goals**
- One command that answers "who else is on this connection or this client, and are they banned" from game.
- The same correlation rules as the evasion signal, so the command and `evasion.detected` never disagree.
- No IP address, token or owner hash shown in game; each entry names the account's UUID and name.

**Non-Goals**
- Any action on linked accounts.
- A new correlation rule. The window, the token and the fingerprint links are reused as they are.
- Changing the panel's hints.

## Decisions

### 1. One endpoint, built on the existing correlation

`GET /evasion/{owner}/alts` is answered by `AltLookupService`, which calls the same sources the evasion signal
uses:

- shared address: other owners with a session record whose clear `ip` equals the target's address in the detection
  window, plus other owners with a legacy or session `ip_token` equal to a token of the target in the window
  (the union of the two tables, as in `add-player-intelligence` decision 6). Sessions without an address (the salt
  is unset) still match on the clear IP, so the command works without the salt;
- shared fingerprint: `ClientFingerprintService.linksOf(owner)`.

An owner that appears under both reasons is listed once with both reasons. The target itself is never listed.

Rejected: a second correlation query written only for the command (the two would drift and the command would show
accounts that never raised `evasion.detected`); calling `GET /evasion/{owner}/sightings` and then the fingerprint
route from the proxy (two round trips, and the proxy would have to merge the lists itself).

### 2. Ban status comes from the active slots only

For each linked owner the backend reads the active punishment slots of its profile (`PunishmentProfileEntity.active`,
through the existing profile slot reader) and returns the section keys with an active punishment of kind
`PUNISHMENT`. Kicks are never active (`add-kick`) and are excluded by definition. History is not read, so a lifted
ban does not show.

Rejected: reading the full punishment history per linked owner (N queries and irrelevant rows); using the Redis mirror
on the backend (it is a proxy-side cache, the backend reads its own store).

### 3. Response shape and limit

`AltsDTO.Response` carries `owner`, `name` (the newest name from `add-name-history`, or null), the `linked` list and
`truncated`. Each `Linked` entry has `owner`, `uuid`, `name`, `reasons` (`SHARED_ADDRESS`, `SHARED_FINGERPRINT`), `lastSeen`
(epoch millis) and `activeSections`. The list is newest-first and capped at 20; `truncated` is true when more exist.
The `Error` variant covers a malformed owner and a backend-side failure in place of exceptions.

The owner hash is in the JSON because the proxy uses it for the ban check of each account. The proxy prints the UUID and the name, never the owner hash.

Rejected: returning the IP or the token (not needed by the command; the panel's hints do not show them either).

### 4. Velocity resolves the target in three steps and never blocks the server thread

`/alts <player>` resolves the target as: an online player on this proxy (owner from the hash, no network), then
`GET /player/resolve/{name}`, then `GET /intel/names/{name}/owners` from `add-name-history`. The backend call runs on an
async executor and is awaited with a timeout of `blackhole.alts.timeout` (default 3 seconds, set in Velocity's config).
On timeout the player gets "Alt lookup timed out, try again" and the lookup is not retried.

Rejected: a synchronous call on the command thread (a slow backend would stall the proxy's command handling).

### 5. Clickable entries suggest, they do not run

Each entry is an Adventure component with a hover (reasons in words, last seen) and a click event
`ClickEvent.suggestCommand("/blackhole <name> info")`. The staff member sees the command in the chat box and sends it
themselves. A click never runs a command for them, so a misclick cannot issue a punishment, and nothing is sent to the
backend by the click.

Rejected: `runCommand` (one misclick would run the command with the staff member's permissions); a click that copies the
name only (less useful than opening the info).

### 6. Permission and sender

The command requires `blackhole.alts` and a player sender, as the other `blackhole` lookup commands do. Console use is
out of scope (open question 2). The backend route is open to PROXY and to VIEWER and above, as the proxy calls it on
behalf of staff; the backend has no per-actor identity (`openspec/config.yaml`, principle 2 and the actor-identity note),
so the permission check happens in Velocity only.

### 7. Messages follow the command-message convention

The messages are added to the English and German property files and follow the convention of the in-flight
`improve-velocity-command-messages` change. If that change lands first, its helper is used; the proposal does not depend
on its order.

## Risks / Trade-offs

- **Shared addresses are common** (households, mobile carriers, cafés). The list can be long and a shared address alone is
  weak evidence. The command lists the reason next to every entry and does not call an account an alt; the wording says
  "shares an address with".
- **Stale fingerprint links** if the rules change is partly rolled out. The reason then appears late; nothing breaks.
- **Load**: one endpoint call per command; the correlation queries use the existing token and IP indexes.

## Migration plan

1. Merge `add-player-intelligence` and `add-name-history`.
2. Ship the backend route, then the regenerated client, then the Velocity command.
3. Rollback: remove the command; the route can stay.

## Open questions

1. Should the panel's "Possible alt accounts" panel move to `GET /evasion/{owner}/alts` so the panel and the command show
   the same list (it would then also show the ban status)?
2. Should `/alts` be usable from the console and from Discord?
3. Should `activeSections` for a linked account also be shown in the panel?
