# Phase 0 Research: Ban-Evasion Detection

No `NEEDS CLARIFICATION` markers remained in the Technical Context — this is a
retroactive plan, so every technical question was resolved by reading the shipped
implementation rather than researched from scratch. This document records the key
design decisions actually embedded in that code, in the same Decision/Rationale/
Alternatives format forward-looking research would use, so future changes to this
feature can be evaluated against *why* it works this way rather than just *that* it
does.

## Decision: A keyed HMAC, not a plain hash, of the IP address

**Decision**: `IpCorrelationService.hmacSha512` computes `HMAC-SHA512(rawIp, salt)`
where `salt` is `blackhole.evasion.ip-salt`, a server-held secret — not
`SHA-512(rawIp)` alone.

**Rationale**: The entire IPv4 address space is 2^32 values (IPv6 is larger but still
highly structured/predictable in practice — most real traffic is IPv4 or a bounded set
of IPv6 prefixes). An unsalted hash of an IP is trivially reversible by brute force: an
attacker with database access could precompute `SHA-512` of every routable IPv4 address
in minutes and reverse every token in the table. Keying the hash with a secret that never
leaves the server closes that off — reversing a token now requires the secret itself, not
just compute (spec FR-002: "computationally infeasible even given the small size of the
address space being hashed").

**Alternatives considered**: A plain unsalted `SHA-512(ip)` was rejected for the
brute-force reason above — it would satisfy "one-way" in the narrow mathematical sense
but not in practice, since the input space is small and enumerable. A per-record random
salt (stored alongside the hash, as is standard for password hashing) was also rejected:
correlation requires the *same* IP to always produce the *same* token across different
accounts and different logins, which a per-record salt would defeat entirely — the salt
here must be shared and stable, not per-row.

## Decision: The endpoint hard-fails (503) rather than degrading when unconfigured

**Decision**: `IpCorrelationService.isConfigured()` returns `false` when
`blackhole.evasion.ip-salt` is blank/unset. `EvasionController.record` checks this first
and returns `HttpResponse.status(503, ...)` without ever calling `recordLogin` — no
sighting is recorded, no hash is computed with a fallback/default key.

**Rationale**: The alternative — silently hashing with an empty string or a hardcoded
default salt — would produce a token that is either trivially guessable (empty-string
key) or identical across every deployment that forgot to configure the salt (hardcoded
default), defeating the entire privacy premise of FR-002 while looking like it works.
Failing loudly and specifically (503, not a generic 500) lets the caller (Velocity's
`PlayerLoginListener`) distinguish "detection is off, by design, until configured" from
an actual server error, and the spec's User Story 2 Acceptance Scenario 3 explicitly
requires the system to "report plainly that evasion detection is unavailable rather than
silently proceeding with a weaker, guessable signal" (FR-006).

**Alternatives considered**: Falling back to an unsalted hash with a warning log was
rejected as exactly the silent-degradation failure mode FR-006 exists to prevent — a
log line is easy to miss in production; an explicit 503 on every login is not. Returning
200-with-a-flag (recorded but flagged as "insecure") was rejected as adding a response
shape variant for a condition that should simply not record anything at all.

## Decision: The login flow itself never blocks or fails on this check

**Decision**: `PlayerLoginListener.recordEvasionSignal` (Velocity side) wraps the
`POST /evasion/record` call in a try/catch that only logs at `debug` level on
`ApiException` — including the 503-unconfigured case — and always continues to the rest
of the login flow (ban check, session recording) regardless of outcome.

**Rationale**: This is a detection signal, not a gate — spec Edge Cases explicitly states
"the login itself still proceeds through the rest of the join flow; only the
evasion-specific check is skipped" when configuration is absent. Blocking login on a
side-channel detection call (or on a transient backend/RabbitMQ hiccup) would turn an
observability feature into an availability dependency for every player's login, which is
disproportionate to what this feature is for.

**Alternatives considered**: Failing the login (or delaying it) when the evasion call
errors was rejected — it would make an admin's decision to leave the salt unconfigured
(or a backend blip) into a network-wide login outage, which is a far worse failure mode
than simply missing a detection signal for that one login.

## Decision: Detection window and retention window are separate, independently configured values

**Decision**: `blackhole.evasion.detection-window-days` (default 7) bounds how far back
`checkEvasion` looks for other accounts sharing a token. `blackhole.evasion.retention-days`
(default 90) bounds how long a sighting row is kept before `IpCorrelationRetentionSweeper`
deletes it outright. The retention window is always the larger of the two by default, and
nothing in the code ties them together — they are two independent `@Value` injections.

**Rationale**: These answer different questions. The detection window answers "is this
correlation still *meaningful*" (spec US3: an IP shared a year apart, e.g. after
reassignment, isn't evasion) — a short window keeps false positives down. The retention
window answers "how long is it *justified to keep this personal data at all*" — a GDPR
question independent of whether the data is still detection-useful. Collapsing them into
one value would force a choice between over-retaining data past its detection usefulness
(to satisfy some other requirement) or deleting data the detection window still needs
mid-window. Keeping them separate lets an operator tune investigation usefulness and
privacy-minimization cost independently (FR-009, SC-006).

**Alternatives considered**: A single `window-days` value used for both purposes was
rejected for the reason above — it conflates two independent concerns that the spec
(FR-004 vs. FR-008) already treats as separate requirements.

## Decision: Retention is enforced by outright deletion, not anonymization

**Decision**: `IpCorrelationRetentionSweeper.sweep()` calls
`repository.deleteAll(expired)` on every row whose `lastSeen` is older than the retention
cutoff — there is no soft-delete, tombstone, or anonymize-in-place step.

**Rationale**: The stored token is already a one-way keyed hash with no raw IP anywhere
in the row — there is nothing left in an expired row to further anonymize that isn't
already the minimum needed for correlation to function at all. Once retention has
elapsed, the row has no further legitimate use (correlation past the detection window
isn't performed anyway) and continuing to hold it would be pure liability with no benefit,
consistent with the project's GDPR-by-design stance (constitution Principle I) and the
same pattern `PunishmentExpirySweeper`/`EloDecaySweeper` establish elsewhere in the
codebase (see this sweeper's own class Javadoc, which calls this out explicitly).

**Alternatives considered**: Soft-deleting (a `deletedAt` flag) was rejected as adding
retained rows with no legitimate purpose once past the window — exactly what the sweeper
exists to prevent.

## Decision: This feature is detection-only — it publishes an event and stops

**Decision**: `IpCorrelationService.checkEvasion` ends with
`eventPublisher.publish("evasion.detected", Map.of("token", token, "owners",
distinctOwners))`. Nothing in the `evasion/` package applies a punishment, mutates an
ELO score, or takes any other consequential action, and no code anywhere in this
repository currently subscribes to `evasion.detected` (confirmed by search — the only
reference to that string is the `publish` call itself).

**Rationale**: Deciding whether a shared IP genuinely warrants a punishment requires
context this feature deliberately doesn't have — household/NAT/VPN sharing produces the
same signal as genuine evasion, and only a human or a purpose-built downstream policy
should make that call (spec Assumptions, matching the same "detect, don't decide" split
the `elo/` engine draws between chat-toxicity scoring and punishment application). Firing
a domain event rather than calling into the `punishment` package directly also avoids
this feature owning a dependency on punishment internals it has no other reason to know
about (FR-010).

**Alternatives considered**: Auto-applying a punishment (or an ELO delta) directly when
`distinctOwners.size() > 1` was rejected — an automatic ban on a signal this prone to
false positives (shared households, NAT, VPN exit nodes) would be far more disruptive
than a missed detection, and the spec's own framing treats this as strictly out of scope,
deferring any consequence to `specs/002-punishment-core/spec.md`. Nothing was rejected in
favor of building a consumer as part of this feature — none exists yet, and this document
records that honestly rather than describing planned-but-unbuilt behavior as if it
already existed.

## Decision: Duplicate sightings refresh in place instead of accumulating rows

**Decision**: `recordLogin` looks up an existing row by `(token, ownerHash)` first; if
found, it only updates `lastSeen` and increments `occurrenceCount` on that same row
rather than inserting a new one.

**Rationale**: Without this, a single account reconnecting from its usual IP thousands of
times would create thousands of rows, all sharing the same `(token, ownerHash)` pair —
none of which is ever a distinct additional account, so `checkEvasion`'s
`distinct(ownerHash)` would still correctly report one owner, but at the cost of an
ever-growing table with no detection value and no way for the retention sweeper to ever
catch up (`lastSeen` would be refreshed on every login, but so would a stack of stale
duplicate rows under the same key). One row per `(token, ownerHash)` pair keeps the table
sized to actual distinct pairs and matches spec FR-007 exactly.

**Alternatives considered**: Always inserting a new row and deduplicating at query time
in `checkEvasion` was rejected as pushing the same cost (and the multiplying-rows
problem) into every read instead of paying it once on write.
