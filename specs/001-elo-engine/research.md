# Phase 0 Research: Dual-ELO Punishment Engine

No `NEEDS CLARIFICATION` markers remained in the Technical Context — this is a
retroactive plan, so every technical question was resolved by reading the shipped
implementation rather than researched from scratch. This document records the key
design decisions actually embedded in that code, in the same Decision/Rationale/
Alternatives format forward-looking research would use, so future changes to the
engine can be evaluated against *why* it works this way rather than just *that* it
does.

## Decision: One write path for every score mutation

**Decision**: `EloService.applyDelta` is the only method that ever changes a
`chatElo`/`gameplayElo` score. Chat toxicity, anticheat signals, actioned reports,
manual adjustments, and decay recovery all call into it rather than writing scores
independently.

**Rationale**: The threshold check (soft/hard auto-punishment) and the audit-trail
write (`EloEventEntity`) both live inside this one method. Any second write path would
risk silently skipping either — an unaudited or unenforced score change would defeat
the point of an *automatic* punishment system (spec FR-004, FR-009).

**Alternatives considered**: Letting each signal source (chat, anticheat, reports)
write its own score update and separately call a shared "check thresholds" helper was
rejected — it would require every future signal source to remember to call the checker,
making the bypass a one-line-omission away instead of structurally impossible.

## Decision: Only a genuine downward crossing triggers an automatic punishment

**Decision**: `checkThresholds` compares `previousScore` and `newScore` and only fires
when the player was at/above a threshold and is now below it — not merely "is currently
below."

**Rationale**: Without this, a player already sitting below the soft threshold would
receive a new automatic punishment on *every* subsequent violation, which is neither
useful (they're already punished) nor intended (spec FR-007, Edge Cases).

**Alternatives considered**: Firing on every evaluation where the score is below
threshold (simpler code, one fewer local variable) was rejected for the reason above.

## Decision: Hard/perma-ban tier requires an explicit template; soft tier auto-generates one

**Decision**: Crossing the soft threshold finds-or-creates a punishment template on
first use (`findOrCreateSoftAutoTemplate`). Crossing the hard threshold only applies a
punishment if network operators have already configured `blackhole.elo.perma-ban-
template.{chat,gameplay}-id`; if not, the system logs and skips rather than fabricating
one.

**Rationale**: A temporary soft-tier punishment with a reasonable auto-generated
duration is low-risk to invent on the fly. A *permanent* ban is the system's most
severe, least reversible consequence — inventing unreviewed wording/duration for that
was judged worse than occasionally skipping enforcement until an operator configures it
(spec FR-006).

**Alternatives considered**: Auto-generating a default perma-ban template the same way
the soft tier does was rejected for the reversibility reason above.

## Decision: Chat evidence stores a hash, never the raw message

**Decision**: `ChatToxicityService` computes `SecretHasher.hash(message)` and persists
only that hash plus a retention-expiry timestamp in a `PunishmentEvidenceEntity`
(`EvidenceType.CHAT_MESSAGE`). The raw string is held only long enough in memory to
score and hash it.

**Rationale**: Direct consequence of the project's GDPR-by-design constitution
principle — chat content is personal data, and this feature only needs to *support
review* of a decision (spec FR-003), which a hash + retention window achieves without
retaining the message itself.

**Alternatives considered**: Storing the raw message for a short review window (easier
for operators to see *what* was flagged) was rejected as inconsistent with the
project's PII-handling stance.

## Decision: Decay is lazy on-write, backstopped by a nightly sweep

**Decision**: `EloService.reconcileDecay` runs inline inside `applyDelta` (and inside
`reconcileDecayForProfile`) whenever a profile is touched, capped so it never overshoots
`baseline`. `EloDecaySweeper` additionally walks every profile nightly
(`@Scheduled(cron = "${blackhole.elo.decay.sweep-cron:0 0 3 * * *}")`, 200-row pages) so
a player who never triggers another write still recovers (spec FR-011).

**Rationale**: Lazy reconciliation alone would leave a player who stops playing
permanently stuck below baseline, since nothing would ever call `applyDelta` for them
again — undermining the "standing recovers over time" story (spec US3) for exactly the
players it matters most for (those who stopped after a violation).

**Alternatives considered**: A pure nightly-sweep-only model (no lazy path) was
rejected as unnecessary — the lazy path gives immediate, correct recovery for active
players without waiting for the next 03:00 run.

## Decision: Deterministic system-actor UUID for automatic punishments

**Decision**: `EloService.SYSTEM_ELO_SOURCE = UUID.nameUUIDFromBytes("blackhole-elo-
system".getBytes(UTF_8))` is used as the `source` for every auto-applied punishment.

**Rationale**: The audit trail needs to distinguish "the algorithm decided this" from
"a human moderator decided this" from "this came from a vanilla-ban-list import" (spec
FR-008) — a fixed, well-known UUID makes that distinction queryable without a real
per-actor identity system, which this project deliberately does not have yet
(constitution Principle II).

**Alternatives considered**: A nullable `source` field to mean "system-issued" was
rejected as ambiguous against "unknown/legacy data."

## Decision: No `@Transactional` around the read-modify-write threshold check

**Decision**: The score read, score write, event write, and threshold check in
`applyDelta` are not wrapped in a database transaction.

**Rationale**: Not a preference — `@Transactional` is unusable project-wide due to a
bean-ambiguity collision between two auto-configured `TransactionOperations` beans
(constitution Principle VI). The resulting race window (two simultaneous violations on
the same player+track both reading the same `previousScore`) is accepted and documented
in `EloService`'s Javadoc rather than worked around with a less-safe manual locking
scheme.

**Alternatives considered**: Manual pessimistic locking (`SELECT ... FOR UPDATE` via a
native query) was investigated as a workaround in a prior session and rejected as
disproportionate to a narrow, low-frequency race window; explicitly deferred.

## Decision: Toxicity scoring is a pluggable bean, not hardcoded logic

**Decision**: `ChatToxicityService` depends on the `ToxicityScorer` interface;
`RuleBasedToxicityScorer` (a configurable keyword-substring matcher) is the default
`@Singleton` implementation, explicitly documented as a placeholder.

**Rationale**: Real toxicity classification is a substantial, separately-evolving
problem (and out of this spec's scope per its Assumptions). Making it swappable lets
operators replace the bean without touching `ChatToxicityService` or the scoring
contract (spec FR-014).

**Alternatives considered**: Shipping only the keyword matcher with no interface seam
was rejected — it would force a breaking change on any operator wanting real
moderation-grade scoring later.
