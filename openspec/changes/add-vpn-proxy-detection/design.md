## Context

The code this design builds on (checked on `main`, 2026-10-28):

- `velocity/.../listener/PlayerLoginListener.java` already makes a synchronous backend call per login
  (`recordEvasionSignal`, then the punishment check). A third call adds latency to the same path.
- `backend/.../evasion/` records a keyed HMAC of each login IP, so it cannot classify an address.
- The sightings and the offline ASN/organisation data come from `add-player-intelligence` (prerequisite).
- Owners are SHA-512 hashes of player UUIDs (`UUIDConverter` on the proxy, `punishment-management`).
  An allow-list keyed by the owner hash adds no new personal identifier.
- `@Transactional` is unusable in this codebase (`openspec/config.yaml`, principle 6). Multi-step
  writes need a design that is correct without a transaction.
- Roles: `ADMIN`, `MODERATOR`, `VIEWER` (Entra), `PROXY`, `DISCORD`, `METRICS` (machine).
- Micrometer and scheduled sweepers are already used by the backend.

Blocklist sources, checked on 2026-10-28 at https://github.com/X4BNet/lists_vpn:

- The repository publishes "Lists of VPN providers (automatically updated)", MIT licence,
  copyright 2024 X4B (Mathew Heard). Lists are rebuilt by GitHub Actions "regularly", with no fixed
  schedule.
- `output/vpn/ipv4.txt`: known VPN networks only (may overlap datacenter ranges).
- `output/datacenter/ipv4.txt`: VPN and datacenter ranges, meaning networks that are not eyeball
  networks.
- `ipv4.txt` at the root: a legacy datacenter list that the README says will be removed in 2026. Not used.
- `ipv6.txt` at the root is in the file tree; its contents are not described. Not used until verified.
- The page does not state the line format. The helper script that converts subnets to single
  addresses implies CIDR lines. The first task verifies this against the downloaded file.

## Goals / Non-Goals

**Goals**

- Classify every login address offline, with no per-login external call.
- Give each class a policy that an admin chooses, with `FLAG` as the safe default.
- Let a legitimate VPN user through by an explicit, auditable exemption.

**Non-Goals**

- A verdict that is certain. Lists have false positives (some ISPs run on datacenter ranges, and
  VPN lists overlap). This is why the default is `FLAG` and not `DENY`.
- Detecting proxies that no list knows about.

## Decisions

### 1. Classification: offline, from three inputs

For a login address the classifier returns one of `VPN`, `HOSTING`, `PROXY` or `CLEAN`, with the
source key that matched (for example `list:x4bnet-vpn` or `asn:hosting`) and the ASN and organisation
when known.

Order: (1) a hosting ASN from `blackhole.intel.hosting-asns` (kind `HOSTING`); (2) an entry of an
enabled list source, matched by prefix (its declared kind); (3) otherwise `CLEAN`. When an address
matches several classes, the most restrictive policy applies: `DENY` over `FLAG` over `ALLOW`, and
the hit records every matching class.

Rejected: calling a commercial API per login. It adds latency and a third party to the login path, and
it sends player addresses off the network. A later change may add it behind a cache, with its own
privacy review.

### 2. List sources, refresh and atomic swap without transactions

- Sources are configuration only: `blackhole.intel.lists[*]` with `key`, `kind`, `url`, `enabled`,
  `attribution`. Default entries point at the two X4BNet files (`output/vpn/ipv4.txt`,
  `output/datacenter/ipv4.txt`) with `enabled: false` until the first refresh is verified by an
  admin. The URLs are configured, so a panel user cannot point the backend at an arbitrary address.
- The refresh job runs every `blackhole.intel.lists.refresh-interval` (default `PT6H`) and on the admin's
  "refresh now" action. It is debounced (one run per source per 10 minutes).
- Fetch hardening, even though URLs are configured: HTTPS only; the resolved address must not be
  loopback, link-local, private, unique-local or a metadata address; redirects are followed only to
  the same host; response size capped at 20 MB; connect and read timeouts of 10 s and 60 s; the body
  must parse, and each line must be a valid CIDR or it is counted invalid (more than 5% invalid fails
  the source).
- Atomic swap without a transaction: entries are written in batches with a new `generation` number.
  Only when every batch is written and the count is plausible, the single row in
  `vpn_list_state` (the active generation per source) is updated to the new generation. Readers only
  look at the active generation. Old generations are deleted after the swap. A crash mid-write leaves
  the old generation active and an orphaned new generation, which the sweeper removes.
- Each backend replica loads the active generations into an in-memory prefix structure at start-up
  and when `vpn_list_state` changes (polled every 5 minutes). Replicas do not write the lists; only the
  refresh job does.
- A failed refresh keeps the active generation and records the error on `vpn_list_source`.

Rejected: a `@Transactional` swap (unusable here). Rejected: loading lists into each Velocity proxy
(duplicates the lists across proxies, bypasses the allow-list and the policy, and forces proxy
releases to change the policy).

### 3. Policy per classification, default FLAG

`vpn_policy` holds one row per class (`VPN`, `HOSTING`, `PROXY`) with `ALLOW`, `FLAG` or `DENY`.
Defaults on the first start: all three `FLAG`. Changing a policy to `DENY` requires ADMIN and
records the previous value in the audit trail (the case timeline is not used because this is not a
punishment).

Rejected: default `DENY`. It would refuse legitimate players on hosting-ranged ISPs on day one.

### 4. Per-player allow-list

`vpn_allow_list` holds the owner hash (SHA-512 of the UUID, as elsewhere), a reason (1 to 200
characters), the actor's `oid`, an optional expiry and `created_at`. An allow-listed owner gets
`ALLOW` for every class; the hit is still recorded with `allow_listed = true` so that staff can see
that an exemption applied.

- MODERATOR and ADMIN can add an entry with an expiry up to 90 days; only ADMIN can add a permanent
  one.
- Entries are keyed by owner hash only. No name and no address is stored with the entry.

### 5. The login check: one synchronous call with a timeout

`POST /intel/login-check` (role `PROXY`) takes `owner` (hash) and `ip`, and returns
`{verdict: ALLOW|FLAG|DENY, classification, sourceKey}`. The backend:

1. checks the allow-list for the owner;
2. classifies the address from the in-memory structures (no database read on the hot path);
3. records a hit when the verdict is not `ALLOW`, or when the allow-list applied;
4. returns the verdict.

Velocity calls it after the existing ban and sighting steps, with a timeout of
`blackhole.intel.login-check.timeout` (default `PT1S`, set in the Velocity config). On timeout, error
or an unknown verdict the login is allowed and `blackhole.intel.login_check.failopen` increments.
Fail-open is deliberate: a broken backend must not lock out every player. `DENY` refuses the login
with the `Surface.VPN_DENY` screen, localized through the existing `PunishmentScreens` message bundle,
and the screen contains no address and no account detail.

The sighting recording remains the existing `POST /evasion/record`; this change adds no change to it.

### 6. Hits: what is stored and for how long

`vpn_detection_hit`: `id`, `owner` (hash), `ip` (clear, legitimate interest for review, per the amended
principle), `classification`, `source_key`, `asn`, `organisation`, `verdict`, `allow_listed`,
`created_at`. Hits are shown on the player page to staff only. Retention: `blackhole.intel.hit-retention`
(default `P90D`), enforced by a sweeper that reads an injected `Clock`. A hit never creates a
punishment.

### 7. Staff notice for FLAG

A `FLAG` hit publishes `blackhole.events.intel.vpn.flagged` with the hit id and the class, not the
address or the owner hash, because events can leave the backend (Discord). The Discord bot and the
panel fetch the hit detail through their own roles. Notices are aggregated per owner per 30 minutes,
so a reconnect loop produces one notice.

### 8. Metrics and health

- `blackhole.intel.lists.entries{source}` (gauge), `blackhole.intel.lists.refresh_failures{source}`
  (counter), `blackhole.intel.lists.last_refresh_seconds{source}` (gauge).
- `blackhole.intel.login_check{verdict}` (counter), `blackhole.intel.login_check.failopen` (counter),
  `blackhole.intel.login_check.latency` (timer).
- Health indicator `intel-lists`: DOWN only when every enabled source has failed for longer than two
  refresh intervals. A DOWN indicator does not block logins; the classifier keeps the last good
  generation.

### 9. Attribution and licence

The panel's list page shows each source's attribution (for X4BNet: "X4BNet lists_vpn, MIT, copyright
2024 X4B (Mathew Heard), https://github.com/X4BNet/lists_vpn"). The operator note carries the MIT
notice. Blackhole does not redistribute the lists; it downloads them from the configured URL.

### 10. Hosting ASN list

`blackhole.intel.hosting-asns` is an operator-maintained list of AS numbers, empty by default.
Changing it takes effect at the next classifier reload (within 5 minutes). The list is not shipped
with a fixed set of numbers in code, because the right set depends on the network's players.

## Risks / Trade-offs

- **False positives.** Datacenter lists can cover legitimate ISPs and mobile carriers. Mitigation:
  `FLAG` by default, a visible allow-list, and the hit shows the matching source so a moderator can
  judge.
- **Stale lists.** Lists change daily. Mitigation: 6-hour refresh, the last good generation is kept,
  and the health indicator reports prolonged failure.
- **Login latency.** One extra call per login. Mitigation: a 1-second timeout, fail-open, and a
  latency timer; the call does no database read on the hot path.
- **Fail-open lets VPN users in during an outage.** Accepted: the alternative locks out every player.
  Recorded in the operator note.
- **Clear IP in hits.** Needed for review of a hit. Mitigated by the amended principle's retention
  rule, a 90-day retention, and staff-only access.
- **Source format.** The X4BNet line format is not documented on its page. The first task verifies it
  against the real file; invalid lines are counted, and a source with more than 5% invalid lines fails.

## Migration plan

1. Prerequisites merged (`add-player-intelligence`, `amend-data-protection-principle`).
2. Migration `V<next>__vpn_detection.sql` is additive: `vpn_list_source`, `vpn_list_state`,
   `vpn_list_entry` (with `generation`), `vpn_policy`, `vpn_allow_list`, `vpn_detection_hit`, and the
   seed rows for the three policies (`FLAG`). Rollback drops the new tables; no existing table changes.
3. Deploy the backend with the list sources disabled and policies at `FLAG`. An admin enables one
   source and checks the entry count and invalid count on the list page.
4. Deploy the Velocity login check with the timeout. Until it is deployed, no hits are recorded and
   no DENY applies.
5. Only after a week of `FLAG` hits reviewed by moderators, an admin may switch a class to `DENY`.

## Open questions

1. Hosting ASN starter list: should the project ship a documented starter list, or leave it empty?
2. Should a `DENY` screen offer a way to request an allow-list entry (for example a Discord ticket),
   or is "contact staff" enough?
3. Is a 90-day retention of clear addresses in hits acceptable to the data protection owner, or
   should `FLAG` hits be kept shorter than `DENY` hits?
4. Should `PROXY` class come from any list at all, or is it reserved until a source is chosen?
5. Does the X4BNet `ipv6.txt` cover the IPv6 side we need, or is IPv6 classification limited to the
   ASN path until a source is verified?
