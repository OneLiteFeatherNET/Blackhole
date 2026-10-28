## Why

Players who are banned, or who are evading a ban, often connect through a VPN, a commercial proxy
or a rented server. Staff see only that a new account logged in from "some IP", with no way to tell
a home connection from a datacenter. Blocklists of such addresses exist as open data, but nothing
in Blackhole uses them, and checking an external API for every login would add latency, a third
party in the login path and a cost per login.

Staff need to know which logins come from VPN, hosting or proxy addresses, decide per kind what
should happen (let it pass, flag it for review, or deny it), and let a trusted player with a
legitimate VPN through without changing the rule for everyone else.

## What Changes

- **Offline classification.** The backend classifies each login address using data it already holds
  offline (ASN and organisation from `add-player-intelligence`), a configured list of hosting ASNs,
  and open blocklists of VPN and datacenter ranges, refreshed by a scheduled job. No external API is
  called per login by default.
- **Open blocklists, configured not typed in.** The backend refreshes the X4BNet `lists_vpn` lists
  (MIT licence, see sources in `design.md`) from configured URLs. Sources are set in configuration
  (environment), not from the panel. Each source carries its kind (`VPN`, `HOSTING` or `PROXY`), an
  attribution and a refresh status. A failed refresh keeps the previous list.
- **Policy per result.** Each classification has a policy of `ALLOW`, `FLAG` or `DENY`:
  - `ALLOW`: nothing happens.
  - `FLAG`: the login proceeds, a hit is recorded, and staff are notified.
  - `DENY`: the login is refused with a screen that says VPN and hosting connections are not allowed
    on this network, with no address and no account detail.
  Defaults: `VPN`, `HOSTING` and `PROXY` are all `FLAG`. `DENY` is an explicit choice by an admin.
- **Per-player allow-list.** An admin or moderator can exempt a player (by their owner hash) from
  `FLAG` and `DENY` for a stated reason and optional expiry. The exemption is visible in the player
  page and in the policy page.
- **Panel.** A policy page (ADMIN) for the three policies, the hosting ASN list and the list sources
  with their refresh status and attribution; an allow-list page (MODERATOR and ADMIN); hits shown on
  the player page.
- **Login path.** One backend call per login, `POST /intel/login-check`, synchronous, with a short
  timeout. If it times out or fails the login is allowed and the failure is counted. It runs after the
  existing ban checks.

## Capabilities

### New Capabilities

- `vpn-proxy-detection`: the classification of login addresses, the list sources and their refresh,
  the hosting ASN list, the policy per result, the per-player allow-list, the hit record and its
  retention, and the admin and staff access rules.

### Modified Capabilities

- `proxy-enforcement`: the login check for the VPN policy, the `DENY` screen, the timeout behaviour
  and the fail-open rule. The `FLAG` path does not change what the player sees.
- `admin-web-ui`: the policy page, the allow-list page, the list source status and hits on the player
  page.

## Out of scope

- Per-login calls to commercial VPN or proxy APIs. Adding one later is a separate change that would
  need its own privacy review and a contract for the timeout.
- Detecting a VPN from the client (launcher or mod checks, client brand heuristics). Client rules stay
  in their own capability.
- Automatic punishment for a VPN login. A hit never creates a punishment; `DENY` refuses the login
  only. Staff who want a ban create one through the usual flow.
- Publishing hits or lists anywhere outside the staff panel. Public pages and third-party events never
  carry an address or a hit.
- Rate limiting of the login check. Infra owns rate limiting (`config.yaml` principle 5).
- Detection of residential proxies from behaviour, and any ML scoring.
- Changing the ban evasion detection or the sighting record (`evasion-detection`).

## Prerequisites

- `add-player-intelligence`: the offline ASN and organisation for a login address, and the sightings
  that the policy evaluates. Not yet on `main`. This change does not start before it is merged.
- `amend-data-protection-principle`: hits store the clear address for review, for a bounded retention.
  Not yet on `main`.
- `add-ip-bans-via-firewall` is not a prerequisite. If it is merged first, the login chain runs the
  IP ban check before the VPN check; if not, the VPN check runs after the existing ban check only.

## Impact

- **Backend**: a new `intel/` feature package (classification service, list refresh job, policy
  service, allow-list, hit store, login-check controller, retention sweeper), `application.yml`
  (`blackhole.intel.*`).
- **Database**: one Flyway migration `V<next>__vpn_detection.sql`, the next free version at
  implementation time (main is at V27 today). Additive only. The list generation pointer is a table
  row, not a transaction, because `@Transactional` is unusable in this codebase.
- **API**: `POST /intel/login-check` (role PROXY), policy and allow-list routes under `/intel` for the
  panel. `client/specs/blackhole-api-*.yml` gets the next free version. This is a new route only and
  breaks no existing client.
- **Velocity**: one login-check call, the `DENY` screen (localized through the existing screens), a
  timeout setting. No lists in the proxy.
- **Web**: the policy page, the allow-list page, the list status on the policy page, a hits section on
  the player page.
- **Licence**: the X4BNet lists are MIT; the attribution is shown in the panel and the operator note.

Ships as: feat(intel): flag and deny vpn and hosting connections
