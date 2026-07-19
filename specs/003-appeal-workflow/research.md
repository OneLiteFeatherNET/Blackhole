# Phase 0 Research: Appeal Workflow

No `NEEDS CLARIFICATION` markers remained in the Technical Context — this is a
retroactive plan, so every technical question was resolved by reading the shipped
implementation rather than researched from scratch. This document records the key
design decisions actually embedded in that code, in the same Decision/Rationale/
Alternatives format forward-looking research would use, so future changes to the
workflow can be evaluated against *why* it works this way rather than just *that* it
does.

## Decision: A fixed, versioned checklist gates review — never pure auto-lift, never free-form staff judgment

**Decision**: Every submitted appeal is evaluated by `AppealEligibilityService.evaluate`
against a deterministic set of factors (minimum wait elapsed, no recent denied/ineligible
repeat, severity tier, supporting standing signal) before a human ever sees it. The result
only decides *eligibility to be reviewed* — it never itself grants or denies the
underlying punishment.

**Rationale**: `AppealEligibilityService`'s own class Javadoc states the reasoning
directly: a pure auto-eligibility gate that also auto-decided the appeal would be a
farming vector (rack up clean days, get auto-lifted), while unconstrained human
discretion with no objective gate was "today's original problem this whole system exists
to fix" (spec US2, FR-003/FR-006).

**Alternatives considered**: Letting reviewers see every submitted appeal regardless of
elapsed time or repeat history (simpler, no gate to design) was rejected as reintroducing
exactly the unfair, inconsistent review problem the checklist exists to solve.

## Decision: Auto-issued punishments get a shorter minimum wait than manually-issued ones

**Decision**: `minDaysRequired` is `blackhole.appeal.min-days-auto` (default 3) when
`EloService.SYSTEM_ELO_SOURCE.equals(punishment.getSource())`, and
`blackhole.appeal.min-days-manual` (default 14) otherwise.

**Rationale**: Documented in `.claude/rules/appeal.md` — auto-bans exist to save
moderator time, so appealing one shouldn't cost more of it via a longer mandatory wait
than a punishment a human deliberately chose to issue. The comparison is source-based
(the ELO engine's own fixed system-actor UUID, see `specs/001-elo-engine/research.md`
"Deterministic system-actor UUID"), not a separate flag — reusing an identity the ELO
engine already established rather than inventing a second "was this automatic" marker
(spec FR-004, SC-003).

**Alternatives considered**: A single uniform minimum wait for all punishments
(simpler config surface) was rejected — it would make the automatic path strictly slower
to contest relative to the moderator time it was designed to save, defeating the point of
FR-004/SC-003 ("the automatic path is never slower to appeal").

## Decision: The repeat-appeal cooldown only looks at denied/ineligible priors, never pending ones

**Decision**: `AppealRepository.findByPunishmentIdentifierAndStatusIn` is queried with
`List.of(AppealStatus.DENIED, AppealStatus.INELIGIBLE)` only. A player with an appeal
still `ELIGIBLE_PENDING_REVIEW` against the same punishment is not blocked from
submitting another one.

**Rationale**: Spec Edge Cases states this explicitly — the cooldown exists to stop
gaming a *rejected* outcome by immediately retrying, not to enforce a one-appeal-at-a-time
rule. Blocking a second submission while the first is merely still queued for review
would have no gaming-prevention benefit and would only punish players for staff review
latency they don't control.

**Alternatives considered**: Blocking any second appeal against the same punishment
while one is outstanding in any non-terminal state (simpler single query, no status
filter) was rejected for the reason above.

## Decision: SEVERE tier is `NETWORK` type with no expiration — structurally capped to duration-reduction only

**Decision**: `AppealEligibilityService.isSevere` classifies a punishment as SEVERE when
`punishment.getType() == PunishType.NETWORK && !punishment.getMetaData().containsKey(Expirable.META_DATA_KEY_EXPIRATION_DATE)`
— i.e. a permanent, network-wide ban specifically, not any `NETWORK` punishment and not
any permanent one. The `severe` flag is stored in the checklist snapshot
(`severityTier`) at submission time and enforced by `AppealController.review` refusing
`GRANTED_FULL_LIFT` against it (FR-007/FR-009).

**Rationale**: A temporary network ban already resolves itself on expiry; a permanent
server- or chat-scoped punishment is bounded in a way a permanent network ban is not
(the player can still access the rest of the network, or isn't silenced everywhere). Only
the strict intersection — permanent *and* network-wide — is treated as the ceiling case
severe enough that even a successful appeal can only shorten it, never zero it out
immediately (spec US4).

**Alternatives considered**: Treating every permanent punishment (any type) as SEVERE
was rejected as broader than the spec's stated scope ("the most severe punishment tier,
distinct from every other punishment kind and duration" — FR-007 singles out
permanent+network-wide specifically, not permanence alone).

## Decision: Checklist is versioned so a future rule change can never retroactively reinterpret a decided appeal

**Decision**: `AppealEligibilityService.CHECKLIST_VERSION = 1` is written into every
`checklistSnapshot` as `checklistVersion`, alongside every individual factor considered
(`minDaysRequired`, `daysSincePunishment`, `minTimeElapsed`, `isRepeatAppeal`,
`repeatAppealCooldownDays`, `isAutoTriggered`, `eloTriggerReasonCode`, `severityTier`,
`supportingEloTrack`, `supportingEloScore`, `supportingEloRecovered`, `eligible`) rather
than only the final boolean.

**Rationale**: Spec FR-006 requires both a full audit trail (every factor, not just the
verdict) and version stability — if `min-days-manual` or the SEVERE definition changes
next year, an already-evaluated appeal's stored checklist must still reflect exactly what
it was judged against at the time, not be silently reinterpreted under new rules on next
read.

**Alternatives considered**: Storing only the final `eligible` boolean (smaller JSON
payload) was rejected — it would make it impossible to audit *why* an appeal was ruled
ineligible after the fact, and impossible to distinguish "still valid under current
rules" from "was valid under an earlier version of the rules."

## Decision: Decision mechanics are a fourth, independent implementation of "move active punishment to history"

**Decision**: `AppealDecisionService.applyDecision`'s full-lift branch re-implements the
"move `activeBan`/`activeChatBan` into `history`, clear the active slot" rotation
directly against `PunishmentProfileEntity`, rather than calling
`PunishmentApplicationService.revokeBan`/`revokeMute` (which already do the same
rotation for the manual-revocation path). `PunishmentApplicationService.revoke`'s own
Javadoc calls this out explicitly: it is "a deliberately independent 4th implementation
of the 'move active punishment into history' rotation already present in `apply`,
`PunishmentExpirySweeper.sweepProfile` and `AppealDecisionService.applyDecision` — not a
refactor of those, to avoid regression risk in already-working code."

**Rationale**: This is an explicit, acknowledged tradeoff in the code itself, not an
oversight discovered during this planning pass — four copies of similar logic in
exchange for not touching three already-working call sites while adding a fourth.

**Consequence worth flagging** (not called out in the existing Javadoc): the two
implementations are not behaviorally identical. `PunishmentApplicationService.revoke`
stamps `revokedBy` and `Metadata.META_DATA_KEY_UPDATE_DATE` onto the punishment entity
itself before moving it to history; `AppealDecisionService.applyDecision`'s full-lift
branch does neither — it moves the punishment straight into `history` with no metadata
recording that an appeal (or who) caused the lift. The audit trail for an appeal-granted
full lift therefore relies entirely on the `AppealEntity` itself (`decidedBy`,
`decisionNote`) and the `appeal.resolved` domain event, not on the punishment record. A
review of punishment history alone, without cross-referencing the `appeals` table, cannot
tell an appeal-caused lift apart from any other unattributed history entry — a real gap
against spec FR-017's audit intent, since the *punishment's own* history doesn't carry
it.

**Alternatives considered**: Calling `PunishmentApplicationService.revokeBan`/
`revokeMute` from `AppealDecisionService` instead of reimplementing the rotation was not
attempted, per the existing Javadoc's explicit regression-risk reasoning; this plan does
not second-guess that call, only records the metadata-parity gap it produces as a
finding for `/speckit-tasks` to weigh.

## Decision: Self-review rejection is a best-effort, unverified UUID comparison

**Decision**: `AppealController.review` rejects a decision when
`review.reviewerId().equals(punishment.getSource())`, both client-supplied UUIDs with
nothing behind them proving the caller's real identity.

**Rationale**: Direct consequence of constitution Principle II — there is no per-staff
identity or auth layer in this codebase at all. This is the same kind of unverified
comparison the constitution explicitly anticipates for fields like `source`/`resolvedBy`
(spec FR-011, Assumptions), not a design gap specific to this feature.

**Alternatives considered**: Skipping the self-review check entirely until real identity
exists (simpler, avoids a check that can be trivially spoofed by a dishonest caller) was
rejected — an unverified check still stops the *honest*, well-behaved caller (the normal
Velocity-plugin-mediated path) from self-reviewing by accident, which is worth having
even though it provides no protection against a deliberately malicious caller.

## Decision: A missing standing record defaults to baseline, never "unknown"

**Decision**: `AppealEligibilityService.evaluate` treats `eloProfileRepository.findById`
returning empty as `supportingScore = eloBaseline` (fully recovered), not as a missing or
penalized value.

**Rationale**: Spec Edge Cases and Assumptions state this explicitly as a deliberately
permissive default — a player who has never triggered the *other* ELO track (e.g. never
chatted, if the triggering punishment was gameplay-side) should never be penalized by the
supporting signal for lacking history on a track they simply haven't touched (spec US5).

**Alternatives considered**: Treating a missing profile as a neutral/unknown value shown
separately from "recovered" (more information for the reviewer) was rejected as
unnecessary complexity for a signal that is explicitly informational-only and never gates
anything (FR-018).

## Decision: The supporting standing signal infers its track when the exact trigger isn't recorded

**Decision**: `determineSupportingTrack` prefers the punishment's recorded
`eloTriggerReasonCode` metadata (`TOXICITY_FLAG` → supporting track is `GAMEPLAY`,
`ANTICHEAT_FLAG` → supporting track is `CHAT`) when present, and otherwise falls back to
inferring the *opposite* track from the punishment's own `PunishType` (a `CHAT` punishment
→ supporting track `GAMEPLAY`, anything else → supporting track `CHAT`).

**Rationale**: Only punishments the ELO engine itself auto-issued carry a precise trigger
reason code; a manually staff-issued punishment or a report-actioned one has no such
record. Spec US5 Acceptance Scenario 2 requires the checklist to "still record a
best-effort supporting standing signal, inferred from what kind of punishment it is" in
that case, rather than omitting the field or leaving it null.

**Alternatives considered**: Omitting `supportingEloTrack`/`supportingEloScore` entirely
when no trigger reason code is recorded (simpler, no inference logic) was rejected as
directly contradicting US5 Acceptance Scenario 2's explicit requirement for a fallback.
