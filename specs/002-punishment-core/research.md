# Phase 0 Research: Punishment Core

No `NEEDS CLARIFICATION` markers remained in the Technical Context — this is a
retroactive plan, so every technical question was resolved by reading the shipped
implementation rather than researched from scratch. This document records the key
design decisions actually embedded in that code, in the same Decision/Rationale/
Alternatives format forward-looking research would use, so future changes to the
punishment core can be evaluated against *why* it works this way rather than just
*that* it does.

## Decision: One chokepoint for every apply, `PunishmentApplicationService`

**Decision**: `PunishmentApplicationService.apply` is the only code path that ever
creates a `PunishmentEntity` from a template and installs it into a profile's active
slot. `PunishmentEntityController` (direct staff/service punishment) and the report
resolution flow (per the class's own Javadoc) both call into it rather than each
building a `PunishmentEntity` and rotating the profile's slots themselves.

**Rationale**: Template lookup, expiry-metadata derivation, the "move prior active
punishment into history" rotation, the optional ELO-delta side effect, cache
invalidation, and `punishment.created` event publishing all live inside this one
method. A second write path would risk silently skipping any one of them — most
dangerously the history rotation (spec FR-004/FR-009) or the ELO no-double-dip check
(spec Edge Cases), either of which would be a correctness bug invisible until a
specific edge case was hit in production.

**Alternatives considered**: Letting each caller (staff controller, report resolution,
future auto-punishment sources) build its own `PunishmentEntity` and call a shared
"rotate slot" helper was rejected for the same reason `EloService.applyDelta` rejected
per-source score writes — every future caller would have to remember every step,
turning a structural guarantee into a convention that is one omission away from
breaking.

## Decision: At-most-one-active-punishment-per-track is a DB constraint, not just application logic

**Decision**: `punishment_profiles.active_ban_identifier` and
`.active_chat_ban_identifier` are each declared `unique` in the Liquibase changelog
(`UKjenhq1urh55q6qbhxbor43mpr`, `UK3bfpsh5gh8uybcnqn6ist0ju3`), on top of
`PunishmentApplicationService.apply` already moving any prior active punishment into
`history` before installing the new one.

**Rationale**: Spec FR-004/SC-002 require the guarantee to hold "at any point in
time," including under the `@Transactional`-unusable race window documented in
Technical Context. Two concurrent `apply` calls on the same owner+track racing past
the application-level rotation would, absent this constraint, both attempt to write a
punishment into the same slot; the unique constraint turns that into a rejected second
write (an exception the caller sees) rather than a silently corrupted or ambiguous
"two active punishments" state.

**Alternatives considered**: Relying solely on the application-level rotation (read
profile, see no active punishment, install new one) was rejected as insufficient
precisely because `@Transactional` is off the table project-wide — a purely
application-level guarantee has no defense against the concurrent-write race, whereas
a unique DB constraint does, even though it surfaces as an ugly exception rather than a
clean conflict response.

## Decision: Expiry is checked both lazily on read and via a periodic sweep

**Decision**: `ProfileController.getById` checks `PunishmentExpiry.isExpired(...)` on
both active slots every time a profile is read, moving an expired punishment into
history and updating the row inline before returning it. Independently,
`PunishmentExpirySweeper` runs a `@Scheduled(fixedDelay = "1m")` job that pages every
profile with a non-null active slot and performs the same rotation.

**Rationale**: The lazy check makes spec FR-007's "no person has to manually lift it"
guarantee hold *instantly* for any caller that happens to read the profile, without
waiting for the next sweep tick (spec US3 Acceptance Scenario 1: "even before any
periodic housekeeping has run"). The sweep exists for the case no one reads that
profile again for a while — without it, a temporary punishment on a player who never
gets looked up again would sit in the "active" slot indefinitely even though it should
no longer be enforced, and — critically — Redis/proxies would never be told to clear
it, since nothing would ever trigger the `punishment.expired` event that
`RedisSyncConsumer` needs (spec SC-003's "within at most 1 minute" bound comes directly
from the sweep's `fixedDelay`).

**Alternatives considered**: A sweep-only model (no lazy path) was rejected as
unnecessary latency for the common case of a profile actually being looked at — the
lazy path gives correct-and-immediate results to any direct reader without waiting up
to a minute. A lazy-only model (no sweep) was rejected because it leaves the
network-wide propagated state (Redis) permanently stale for any player nobody happens
to query again, which spec FR-007's "for the network-wide propagated state" clause
explicitly requires to self-correct.

## Decision: Redis propagation is best-effort with no retry/redelivery

**Decision**: `RedisSyncConsumer` consumes off a shared `RabbitTopology.REDIS_SYNC_QUEUE`
using the raw RabbitMQ client rather than `@RabbitListener`, and always
`channel.basicAck`s after a single attempt — even when `handle(...)` throws and the
write to Redis never happened. `PunishmentRedisWriter` itself catches every exception
around its Lettuce calls and only logs, never propagating a failure back to its
caller.

**Rationale**: This is the network-wide propagation mechanism the spec's Assumptions
section explicitly calls "best-effort and eventually consistent by design ... an
accepted design property, not a defect to eliminate." A missed Redis write self-heals
the next time that profile is mutated again (any subsequent apply/revoke/expiry
republishes the full current state for that slot), and every proxy is expected to fall
back to an HTTP call to the backend on a local cache miss — so redelivering a failed
message would risk processing the same domain event twice for no correctness benefit,
while blocking or retrying indefinitely would risk this side channel backing up the
primary domain-event queue.

**Alternatives considered**: A retry-with-backoff or dead-letter-queue approach (safer
against transient Redis outages) was rejected as disproportionate — the self-healing
property already bounds the damage of a missed write to "stale until the next mutation
or a cache-miss fallback," which spec SC-001/SC-004's "few seconds" bound already
budgets for, and a real network partition would defeat a bounded retry anyway.

## Decision: Evidence stores a hash and a bounded retention window, never raw content

**Decision**: `PunishmentEvidenceEntity` has `capturedContentHash` (a `String`) and
`retentionExpiresAt` (nullable `Long` epoch millis) but no raw-content column at all —
there is structurally nowhere to put a raw chat message or screenshot even if a caller
wanted to.

**Rationale**: Direct consequence of the project's GDPR-by-design constitution
principle, and consistent with how the ELO engine's own chat-evidence write path
(`ChatToxicityService`, documented in `specs/001-elo-engine/research.md`) already
solved the identical problem — this feature reuses that entity rather than
reinventing evidence storage, per spec FR-011 and the spec's Assumptions section
("Evidence retention/erasure follows the same bounded-window, hash-only pattern
already established elsewhere").

**Alternatives considered**: None re-litigated here — this decision was inherited from
the ELO engine's prior art rather than made independently for this feature; see
`specs/001-elo-engine/research.md`'s equivalent entry for the original rationale.

## Decision: Deterministic system-actor UUID prevents ELO double-dipping

**Decision**: `PunishmentApplicationService.apply` only applies a template's
`eloDelta` when `!EloService.SYSTEM_ELO_SOURCE.equals(source)` — i.e. it skips the ELO
side effect entirely when the punishment being applied was itself issued by the ELO
engine's own auto-punishment path.

**Rationale**: `EloService` calls back into `PunishmentApplicationService.apply` to
issue an auto-ban when a threshold is crossed, passing its own well-known
`SYSTEM_ELO_SOURCE` UUID as `source`. Without this guard, that auto-ban's template
(if it happened to carry a nonzero `eloDelta`) would apply *another* ELO delta,
which could in turn cross another threshold and trigger another auto-ban — the exact
feedback loop the spec's Edge Cases section calls out ("the standing adjustment must
not be applied a second time in this case"). Reusing `EloService`'s existing
deterministic system UUID (rather than inventing a second marker) keeps this a single
well-known value across both features.

**Alternatives considered**: A boolean flag threaded through the call chain (e.g.
`apply(..., boolean skipEloDelta)`) was implicitly rejected in favor of identity-based
detection — the UUID approach means any *other* system-issued punishment source that
reuses the same constant automatically gets the same protection, without every new
caller needing to remember to pass the flag correctly.

## Decision: Revoke is a separate, fourth implementation of "move active punishment to history"

**Decision**: `PunishmentApplicationService.revoke` (private, backing both
`revokeBan`/`revokeMute`) independently implements the same "record it, move it into
history, clear the active slot, invalidate cache, publish event" sequence that
`apply`, `PunishmentExpirySweeper.sweepProfile`, and `ProfileController.getById`'s
lazy-expiry check also each implement separately — the class's own Javadoc explicitly
calls this out as "a deliberately independent 4th implementation ... not a refactor of
those, to avoid regression risk in already-working code."

**Rationale**: This is documented as a conscious tradeoff by whoever wrote it, not an
oversight — consolidating four independently-evolved rotation implementations behind
one shared helper is exactly the kind of refactor that risks subtly changing behavior
in already-correct, hard-to-fully-test code (no test suite exists for this feature —
see plan.md Technical Context). This plan records the tradeoff rather than silently
accepting or reversing it.

**Alternatives considered**: A shared `ProfileSlotRotator` (or similar) helper used by
all four call sites was the implicit alternative the code's own Javadoc weighs against
— rejected at the time for regression risk, not for being technically inferior; worth
revisiting once test coverage exists to make such a refactor safe (see plan.md
Complexity Tracking and Constitution Check Principle III/VI notes for related
follow-up recommendations).
