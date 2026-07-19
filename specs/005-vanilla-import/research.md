# Phase 0 Research: Vanilla Ban-List Import

No `NEEDS CLARIFICATION` markers remained in the Technical Context — this is a
retroactive plan, so every technical question was resolved by reading the shipped
implementation rather than researched from scratch. This document records the key
design decisions actually embedded in that code, in the same Decision/Rationale/
Alternatives format forward-looking research would use, so future changes to the import
path can be evaluated against *why* it works this way rather than just *that* it does.

## Decision: Import bypasses `PunishmentApplicationService` and writes entities directly

**Decision**: `VanillaImportService` constructs `PunishmentEntity`/`PunishmentProfileEntity`
itself and calls `PunishmentRepository`/`PunishmentProfileRepository` directly, rather
than calling `PunishmentApplicationService.apply` — the chokepoint every other
punishment-creation path in the codebase (staff-issued punishments, ELO auto-punishments,
report resolution) goes through.

**Rationale**: Two requirements of this feature have no equivalent in the shared
chokepoint as written: (1) FR-011 requires imports to **never** apply an ELO delta,
whereas `apply` unconditionally forwards `template.getEloDelta()` to `EloService`
whenever it's nonzero and the source isn't `EloService.SYSTEM_ELO_SOURCE`; (2) FR-004
requires an entry whose expiry has already passed to be filed straight into profile
history as already-inactive, whereas `apply` always writes into the *active* ban slot —
there is no "this decision is already over" code path in it. Rather than extend the
shared chokepoint with parameters (`applyElo: boolean`, `forceHistory: boolean`) to cover
a one-time bulk-migration use case, the import path re-implements the narrow subset of
`apply`'s behavior it needs (template lookup/creation, metadata construction using the
same `Metadata`/`Expirable` key constants, active/history slot rotation, cache
invalidation, event publishing) inline.

**This is a genuine, load-bearing deviation, not a paper-over.** Its safety today rests
entirely on every template `findOrCreateTemplate` reaches having `eloDelta == 0` — which
happens to be true only because that method *creates* fresh templates with `eloDelta`
defaulted to `0`, never looks up a *pre-existing* template by anything other than exact
reason-text match. If an operator (or a future feature) ever causes an import to match an
existing template that carries a nonzero `eloDelta`, FR-011 is violated silently, because
nothing in this path checks or asserts `eloDelta == 0` before proceeding — the guarantee
is incidental, not enforced. See plan.md Complexity Tracking for the recommended
follow-up (either extend `apply` with the two missing capabilities, or add an explicit
`eloDelta == 0` assertion to `findOrCreateTemplate`).

**Alternatives considered**:
- *Extend `PunishmentApplicationService.apply` with `applyElo`/`forceHistory`
  parameters* — the structurally cleaner fix (single chokepoint, no duplicated rotation
  logic), rejected for this retroactive plan only because it changes already-shipped
  behavior rather than documenting it; recommended as a real follow-up task, not
  dismissed.
- *Give imported bans a template with `eloDelta` forced to `0` regardless of lookup* —
  would close the gap without touching the shared chokepoint, but doesn't fix the
  history-only-entry gap (2) above, so `apply` would still need extending or bypassing
  for that case.

## Decision: A third, distinct system-actor UUID for imports

**Decision**: `VanillaImportService.SYSTEM_IMPORT_SOURCE =
UUID.nameUUIDFromBytes("blackhole-vanilla-import".getBytes(UTF_8))` is used as the
`source` for every punishment this feature creates.

**Rationale**: The audit trail needs to distinguish three distinct kinds of issuer — a
human staff member (an unverified client-supplied UUID, per constitution Principle II),
`EloService.SYSTEM_ELO_SOURCE` (the automatic standing-based system, spec `001-elo-engine`
FR-008), and this feature's own `SYSTEM_IMPORT_SOURCE` — so a moderator reviewing history
can tell "this ban existed before Blackhole was ever installed" apart from both "a human
decided this" and "the ELO engine decided this" (spec FR-005, Assumptions). Using the
same deterministic-`nameUUIDFromBytes` pattern as `EloService.SYSTEM_ELO_SOURCE` keeps
the convention consistent across the two features that need a well-known system identity
without a real per-actor identity system (constitution Principle II).

**Alternatives considered**: Reusing `EloService.SYSTEM_ELO_SOURCE` for imports too (one
fewer well-known constant) was rejected — it would make an imported legacy ban
indistinguishable from an ELO-engine auto-ban in the audit trail, defeating spec FR-005's
explicit distinguishability requirement. Preserving the vanilla ban list's own `source`
field (the banning operator's display name as recorded by the *source* server) as the
issuer was rejected per spec Assumptions — it is unverifiable, not a real per-staff
identity this project can validate against.

## Decision: Already-expired legacy entries go straight to history, never through the active slot

**Decision**: After saving the `PunishmentEntity`, the service computes `alreadyExpired =
expirationMillis.isPresent() && expirationMillis.get() <= System.currentTimeMillis()` and
branches: if true, the punishment is appended directly to `profile.getHistory()`; if
false (including the permanent/no-expiry case), it's placed into `profile.setActiveBan(...)`.

**Rationale**: A years-old vanilla ban list frequently contains entries whose recorded
expiry has long since passed. Vanilla server software has no expiry-sweep concept of its
own — an expired entry simply stays in the file, inert. Treating every imported entry as
currently active regardless of its own expiry would resurrect bans the source server
itself had already stopped enforcing, contradicting the source data's own semantics (spec
FR-004, US1 Scenario 2).

**Alternatives considered**: Writing every imported entry into the active slot and
relying on `PunishmentExpirySweeper` (the mechanism that normally rotates temporary bans
into history once they expire) to clean up already-expired ones on its next pass was
rejected — it would momentarily present an already-inactive legacy ban as a live
enforcement action (visible to `GET` endpoints, event consumers, and enforcement points)
between import time and the sweeper's next run, an avoidable correctness gap for data
that was never actually active in the first place.

## Decision: Skip-if-active-conflict, not merge or overwrite

**Decision**: Before creating anything, the service looks up the player's existing
`PunishmentProfileEntity` and checks `profile.getActiveBan() != null`; if so, the entry is
counted as `skippedExisting` and no punishment/profile write happens at all for that
entry — not even a history append.

**Rationale**: A player might already have an active Blackhole-native ban issued after
migration began, or from a previous partial/repeated run of the same import. FR-006 and
spec US3 require that decision to be left completely untouched — re-running the same
import file (e.g. after fixing a handful of FR-007 invalid entries) must be idempotent
with respect to already-imported or already-punished players, since operators are
expected to iterate on a legacy file rather than get exactly one shot at it.

**Alternatives considered**: Merging the imported entry into history alongside the
existing active ban (preserving both records) was rejected as unnecessary complexity —
the source data being imported can't out-rank a punishment decision Blackhole itself
already made, so there is nothing useful to reconcile; simply not touching the profile is
sufficient and matches SC-002's "left unchanged... zero duplicate or conflicting
punishments" wording exactly.

## Decision: Dry run reuses the same skip/import decision, just doesn't write

**Decision**: `dryRun` is checked *after* the active-ban-conflict check but *before* any
entity is constructed — `if (dryRun) { imported++; continue; }`. The same UUID-parse,
expiry-parse, and active-ban lookups run in both modes; only the write half is skipped.

**Rationale**: FR-009 requires a preview to report the *same* imported/skipped/invalid
counts a real run would produce. Running every decision-affecting check identically in
both modes, and only branching on whether to persist, is what makes that guarantee
structural rather than something a maintainer could accidentally let drift between two
separately-maintained code paths (spec US2 Acceptance Scenario 2: "the same file is then
submitted for a real import... the real outcome matches what the preview reported").

**Alternatives considered**: A wholly separate `previewVanillaBans` method duplicating
the parse/lookup logic was rejected — two implementations of the same decision logic is
exactly the drift risk FR-009's guarantee depends on not existing.

## Decision: Per-entry fault tolerance via individual try/catch, not whole-file validation

**Decision**: Each entry's UUID parse and expiry parse are wrapped in their own
try/catch inside the per-entry loop; a failure increments `invalid` and appends a
human-readable string to `invalidEntries`, then `continue`s to the next entry. There is
no upfront whole-file validation pass.

**Rationale**: Real vanilla ban files accumulate over years of manual edits and plugin
quirks (spec US4's framing) — a single malformed record must not block every other valid
entry (FR-007). A missing `reason` defaults to `"Imported vanilla ban"`
(`DEFAULT_REASON`) and a missing/blank `created` defaults to `System.currentTimeMillis()`
rather than being treated as invalid at all (FR-008) — only a genuinely unparseable
`uuid` or `expires` value rejects the entry.

**Alternatives considered**: A pre-validation pass that rejects the whole file if any
entry is malformed was rejected outright — it directly contradicts FR-007/US4's premise
that the rest of the file must still import.

## Decision: IP ban entries are counted, logged, and structurally incapable of being imported

**Decision**: `bannedIpsJson` is optional (`@Nullable CompletedFileUpload`); if present,
it's deserialized only to compute `ipEntries.size()` — no field of any `VanillaIpBanEntry`
(including the raw `ip` address) is read individually, stored, or logged. The result DTO
reports `ipsTotal` and `ipsSkipped` (always equal).

**Rationale**: Blackhole has no IP-level punishment concept at all (spec Key Entities:
"Vanilla IP Ban Entry"), so there's no representation to convert these into. Silently
dropping the file rather than acknowledging it was seen would be silent data loss during
a migration — a trust-eroding failure mode independent of whether IP bans are even in
scope (spec FR-010, US5, SC-007). Never reading individual fields off `VanillaIpBanEntry`
beyond the list size is also what keeps a raw IP address from ever being persisted or
logged anywhere (FR-010's "no raw IP address... may be persisted in any form").

**Alternatives considered**: Silently ignoring `bannedIps` entirely (simplest code) was
rejected per FR-010/SC-007. Storing IP ban entries in some placeholder/inert form for
possible future use was rejected as scope creep and a GDPR-by-design risk (constitution
Principle I) — persisting raw IPs "just in case" contradicts the hash-or-don't-store
posture the rest of the system follows.

## Known gap: re-importing an already-expired entry creates a duplicate history record

**Not a decision — a bug found while writing this retroactive plan's quickstart, worth
recording plainly rather than silently working around.** The skip-if-conflict check
(`profile.getActiveBan() != null`, see "Decision: Skip-if-active-conflict" above) only
inspects the profile's *active* ban slot. An entry that was already-expired at import
time is written into `profile.history`, never `activeBan` (see "Decision: Already-expired
legacy entries go straight to history" above) — so that profile's `activeBan` stays
`null`, and the skip check never fires for it on a subsequent run of the same file. The
entry is re-processed as if new: a second `PunishmentEntity` is created and appended to
`history`, and the count is reported as `playersImported`, not `playersSkippedExisting`.
Re-running the same legacy file N times leaves N near-identical punishments in that
player's history.

This contradicts spec US3's stated guarantee — "Independent Test: Import the same ban
list twice in a row and confirm the second run creates no new or duplicate punishments
for players already imported by the first run" — specifically for the subset of entries
that were already expired at import time. It does **not** affect FR-006's narrower
"already has an active network-track punishment" wording (arguably technically satisfied,
since a history-only profile has no active punishment to conflict with) or SC-002 for
currently-active bans, which is why it wasn't caught by a narrow reading of FR-006 alone
— but it clearly violates the *intent* of "re-running the same file is safe" that both
US3's Independent Test and SC-002 ("including when the same file is imported more than
once") describe without qualifying it to active bans only.

**Recommended fix** (not applied by this planning pass — retroactive plans document,
they don't silently patch): extend the skip check to also treat a player with any
`PunishmentEntity` in `history` whose `source == SYSTEM_IMPORT_SOURCE` and whose
originating entry matches (e.g. by template reason + original creation timestamp) as
already-imported, not just "currently active." A simpler, coarser fix — skip whenever
`profile != null` at all, regardless of `activeBan`/`history` contents — would be safer
against this specific duplication but would also incorrectly skip a player whose only
history entry is unrelated to this import (e.g. a normal expired ban issued and already
rotated out through the ordinary staff/ELO path before the import ever ran), so it isn't
a clean drop-in either; flagged for `/speckit-tasks` to resolve deliberately.

## Decision: Import runs through the HTTP API, not a standalone CLI

**Decision**: The entire capability is one `@Post` multipart endpoint
(`POST /admin/import/vanilla`) on a normal, versioned, network-boundary-trusted
controller, annotated `@Operation(hidden = true)` to keep it out of the published Swagger
spec, rather than a separate offline tool/script that writes to the database directly.

**Rationale**: Routing through the normal controller → service → repository path means
every write still fires `DomainEventPublisher.publish("profile.created"/"punishment.created",
...)` and `CacheInvalidationPublisher.invalidate(owner)` exactly like any other
punishment-creation path (FR-012) — dashboards, RabbitMQ event consumers, and
Redis-backed enforcement caches all see an imported ban the same way they'd see a
manually-issued one, with no separate "backfill" code path for them to special-case.

**Alternatives considered**: A standalone CLI/migration script operating directly on the
database was rejected — it would bypass `DomainEventPublisher`/`CacheInvalidationPublisher`
entirely unless it duplicated that wiring itself, defeating FR-012, and would also
duplicate (a third time) the hashing/skip/expiry logic already needed by the HTTP path.

## Decision: `@Operation(hidden = true)` instead of `@Secured`, for FR-014's "not self-service"

**Decision**: FR-014 ("reachable only by trusted operators/tools... MUST NOT be exposed
as a self-service or publicly documented capability") is satisfied purely via
`@Operation(hidden = true)` on the controller method — the endpoint itself carries no
access-control annotation beyond what every other endpoint in this codebase has (none).

**Rationale**: Consistent with constitution Principle II — there is no auth layer to add
`@Secured` to in the first place, and the project's trust model is enforced at the
network/deployment boundary, not the application. "Not publicly documented" (hidden from
Swagger) and "not reachable by untrusted callers" (network boundary) are two different
guarantees; this feature only needed to satisfy the former in-app, since the latter is
already the whole system's standing posture.

**Alternatives considered**: None seriously — adding `@Secured` here alone would be
inconsistent with every other endpoint and would reintroduce exactly the kind of
piecemeal auth the constitution's Principle II explicitly forbids without a
project-wide, explicit decision to bring auth back.
