## Why

Staff decide on alts, VPN use and repeat offenders from what the network saw of a player, but the
backend today keeps almost none of it. Login sightings are stored only as keyed hashes of the IP,
so staff cannot see which address a player used, which provider or country it belongs to, or which
client they came in with. Every later feature that needs that history (alt lookup, name history,
VPN detection, IP bans) would have to collect it again. This change makes the backend the one place
that records each login and session, with the data a moderator needs to read it.

## What Changes

- **Session reporting from Velocity.** Each login attempt becomes a session record: the clear IP,
  the owner hash of the player, the name seen at login, the client brand and version, the protocol
  version, the virtual host, the outcome (`ALLOWED` or `DENIED`), and the session start and end.
  Velocity reports the start after its ban check, the brand when the client sends it, and the end on
  disconnect. Reports run off the login thread with a short timeout; a failed report is logged and
  dropped. A login never waits for the backend.
- **Offline enrichment.** The backend looks up each new IP in a local ASN and country database (DB-IP
  Lite or GeoLite2, both MaxMind DB files) that is mounted into the pod and refreshed by a job. No
  request leaves the network to enrich a login. A missing or unreadable database stores the session
  without enrichment and does not fail the report.
- **Sessions are kept for a fixed retention** (`blackhole.intel.retention-days`, default 90) and
  deleted by a sweeper, like the existing evasion sightings.
- **Panel: sightings on the player page.** A "Sessions" section lists the sessions of a player
  (time, outcome, IP, ASN and organisation, country, client brand and version, protocol, virtual
  host), newest first and paginated. Every role that may open the player page can read it.
- **Existing evasion data keeps working.** Legacy sightings stay as keyed hashes until their own
  retention ends. New sessions also store the keyed token, so the ban-evasion correlation reads both
  tables and needs no backfill.
- **BREAKING (generated client and Velocity plugin):** none of the existing routes change. The new
  routes are additive: a client regenerated from the new spec gains the session and sessions API. The
  Velocity plugin ships in the same change and reports sessions through the new routes; an older proxy
  keeps calling `POST /evasion/record`, which stays in place.

## Capabilities

### New Capabilities

- `player-intelligence`: the session record of each login and session, its offline enrichment, its
  retention, the ingestion route used by proxies and the read route used by the panel.

### Modified Capabilities

- `evasion-detection`: sightings from session reports store the clear IP and the keyed token; the
  keyed-hash rule is kept for the legacy rows; correlation and retention read both tables.
- `proxy-enforcement`: login enforcement reports a session (outcome included) instead of an evasion
  sighting; the proxy reports the session start, the brand and the end without blocking the login.
- `admin-web-ui`: a "Sessions" section on the player page; IP addresses join the list of values the UI
  must not keep in browser storage.

## Prerequisite

- `amend-data-protection-principle` must be merged before the code PR. It amends principle 1 of
  `openspec/config.yaml` so that clear IP addresses may be stored for a stated feature with a legitimate
  interest basis and enforced retention. This change does not restate that amendment. The owner hash
  remains the only player identifier stored by this feature; raw UUIDs are not stored.

## Impact

- **Backend**: a new `intel/` feature package (controller, service, DTOs, enrichment reader, retention
  sweeper), a reader for the MaxMind DB files, `application.yml` (`blackhole.intel.*`).
- **Database**: one new Flyway migration `V<next>__player_sighting.sql`, additive only (the number is
  assigned at implementation time).
- **API**: `POST /intel/sessions`, `PATCH /intel/sessions/{sessionId}`, `GET /intel/players/{owner}/sessions`.
  `client/specs/blackhole-api-*.yml` gets the next free version.
- **Velocity**: session reporting in `PlayerLoginListener` and the brand listener, a bounded report
  executor, a `disconnect` listener for the end. No new commands in this change.
- **Web**: a sessions section and table on the player page, with unit tests.
- **Deploy**: a mounted volume for the MaxMind DB files and a refresh CronJob in the Helm chart.
- **Config**: `blackhole.intel.geoip.asn-path`, `blackhole.intel.geoip.city-path`,
  `blackhole.intel.retention-days`, `blackhole.intel.retention-sweep-cron`, `blackhole.intel.report-timeout`.

## Out of scope

- Names: the session stores the name seen at login, but the name history view, its lookup and the
  `names` command belong to `add-name-history`.
- Alt lookup: `add-alt-lookup-command` reads these sessions.
- IP bans through the OPNsense alias, VPN and proxy detection (they read this data later and get their own
  changes).
- Playtime totals and analytics; the stored start and end make them possible without a schema change.
- Raw player UUIDs, and any player data in public pages or events to third parties.
- Replacing `POST /evasion/record`; it stays until old proxies are gone.
- Queuing reports for replay after a backend outage. A missed session is accepted.

Ships as: feat(intel): record player sessions with clear IP and offline ASN and country
