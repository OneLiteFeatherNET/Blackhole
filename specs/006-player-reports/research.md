# Phase 0 Research: Player Reports

No `NEEDS CLARIFICATION` markers remained in the Technical Context — this is a
retroactive plan, so every technical question was resolved by reading the shipped
implementation (`ReportController`, `ReportEntity`, `ReportRepository`, and the
`report/dto/` records) rather than researched from scratch. This document records the key
design decisions actually embedded in that code, in the same Decision/Rationale/
Alternatives format forward-looking research would use, so future changes — including the
service-layer extraction flagged in `plan.md`'s Constitution Check — can be evaluated
against *why* it currently works this way, not just *that* it does.

## Decision: Punishment application happens before the report is mutated

**Decision**: In `resolve()`, when `punishmentTemplateId` is present,
`punishmentApplicationService.apply(...)` is called and checked for an empty result
**before** `report.setStatus(...)`/`setResolutionNote(...)`/`setResolvedBy(...)` run or
`reportRepository.update(report)` is called. If the template lookup fails, the method
returns `404` immediately — the report entity in memory is never touched and nothing is
persisted.

**Rationale**: Spec FR-013/SC-006 require that a resolution referencing a vanished
template "never partially completes — no punishment is applied, and the report's status
is never touched." Ordering the punishment call first, and failing fast on an empty
`Optional`, is what actually satisfies that requirement — reversed ordering (mutate the
report first, then attempt the punishment) would leave the report status changed even
when the punishment failed to apply.

**Alternatives considered**: Applying the punishment *after* saving the resolved report
(so the report reflects "actioned" even if the punishment template turned out to be
missing) was rejected — it directly contradicts FR-013's "leaving the report's status...
unchanged" requirement and would require a compensating rollback this codebase has no
transactional mechanism to perform safely (see the `@Transactional` constraint below).

## Decision: Reporter reward is gated on a *demonstrated* punishment application, not the ACTIONED status alone

**Decision**: The reporter-reward `applyDelta` call (`EloReasonCode.REPORT_REWARDED`) is
nested inside `if (resolution.punishmentTemplateId() != null)`, itself nested inside the
`track != null` branch of the `status == ACTIONED` block. Because the method already
returned `404` earlier if a non-null `punishmentTemplateId` failed to apply, reaching this
line with `punishmentTemplateId() != null` true means a punishment was *actually* applied
to the reported player, not merely requested.

**Rationale**: Spec FR-017/FR-018/SC-004 draw a sharp line: "marking a report actioned
alone does not earn a reward" — the reward requires the report to have "demonstrably led
to a real punishment." Reusing the already-guaranteed-non-empty `apply()` result via the
non-null template-ID check (rather than re-checking the `Optional` a second time) is a
direct consequence of the fail-fast ordering decision above — by the time this line
executes, a non-null `punishmentTemplateId` can *only* mean the apply succeeded.

**Alternatives considered**: Rewarding the reporter whenever `status == ACTIONED`,
regardless of whether a punishment template was supplied, was rejected as directly
violating FR-018's explicit prohibition. Re-checking `applied.isPresent()` a second time
at the reward site (instead of relying on the earlier fail-fast return) was unnecessary —
the code already inline-comments this reasoning at the call site.

## Decision: The reported player's standing hit fires on any ACTIONED resolution mapped to a track — independent of whether a punishment was applied

**Decision**: The `EloReasonCode.REPORT_ACTIONED` delta against `report.getReportedHash()`
fires as soon as `status == ACTIONED` and `track != null`, with no dependency on
`punishmentTemplateId` being present at all.

**Rationale**: Spec FR-014 requires the standing reduction whenever "a report is resolved
with an actioned outcome and its category maps to a behavioral standing track" — full
stop, no punishment precondition. This is deliberately asymmetric with the reporter-reward
decision above: a reviewer can mark a report ACTIONED as a standing-only consequence
(e.g. a minor infraction not warranting an immediate punishment template) and still have
it register against the reported player's ELO, potentially contributing toward a *future*
automatic threshold-triggered punishment via `EloService.checkThresholds` even though no
punishment was applied in this specific resolution.

**Alternatives considered**: Gating the reported-player standing hit on the same
"punishment actually applied" condition as the reporter reward (symmetric treatment) was
rejected — it would mean an ACTIONED-without-punishment resolution has *zero* effect,
contradicting FR-014's unconditional wording and removing the ELO engine's ability to
accumulate standing damage across several lower-severity ACTIONED reports that never
individually warranted a punishment template.

## Decision: Two-tier rate limiting — per-reporter, backstopped by a network-wide aggregate

**Decision**: `submit()` checks `countByCreatedAtGreaterThan` (network-wide, no
`reporterHash` filter) *before* `countByReporterHashAndCreatedAtGreaterThan`
(per-reporter), rejecting with `429` on whichever limit is hit first.

**Rationale**: `reporterHash` is client-supplied with no per-actor identity system behind
it (constitution Principle II) — a caller could otherwise defeat the per-reporter limit
by simply varying the hash on every request. `ReportRepository`'s own Javadoc on
`countByCreatedAtGreaterThan` states this explicitly. The network-wide check runs first
in the code, but the ordering between the two checks is not itself load-bearing for
correctness — either limit alone rejects the submission; checking network-wide first
just means a network-wide-exhausted request short-circuits before a second, more targeted
query runs.

**Alternatives considered**: A per-`reporterHash` limit alone (simpler, one query) was
rejected precisely because it's trivially bypassable given the no-auth trust model — this
is the constitution's one sanctioned exception to "no app-level hardening," and it exists
in this two-tier form specifically because a single-tier version wouldn't actually hold
under that trust model.

## Decision: No enforced report-status state machine

**Decision**: `resolve()` unconditionally overwrites `status`/`resolutionNote`/
`resolvedBy`/`updatedAt` regardless of the report's current status — there is no check
that the report is currently `OPEN` or `UNDER_REVIEW`, and nothing prevents resolving an
already-`ACTIONED` or already-`DISMISSED` report again.

**Rationale**: This matches the spec's own Edge Cases and Assumptions sections verbatim
("there is no enforced state-machine over report status... a report actioned a second
time can trigger the standing effect again, since the system does not track whether a
given report already produced one"). The implementation is not working around a missing
feature here — it is the feature as specified, deliberately left unconstrained pending a
future decision.

**Alternatives considered**: None were attempted in the shipped code — spec Assumptions
explicitly defers "whether that should be constrained" as out of scope for this
retroactive specification, so this plan records the current behavior rather than
proposing a fix.

## Decision: `OTHER` category maps to no ELO track

**Decision**: The `switch` in `resolve()` maps `CHAT_ABUSE → EloTrack.CHAT`,
`CHEATING`/`GRIEFING → EloTrack.GAMEPLAY`, and `OTHER → null`; a `null` track short-circuits
both ELO-delta calls entirely for that resolution.

**Rationale**: Spec FR-015/US3 Acceptance Scenario 3 require exactly this — a
catch-all category that "doesn't fit either behavioral track" must produce no standing
change even when actioned. Using `null` as the sentinel (rather than, say, a third
no-op `EloTrack` value) keeps the two behavioral tracks in `EloTrack` itself exactly
mirroring the dual-ELO engine's own two-track model from `specs/001-elo-engine/spec.md`,
with the report package layering its own category-to-track mapping on top rather than the
ELO engine needing to know about report categories at all.

**Alternatives considered**: Adding a third `EloTrack.NONE`/`EloTrack.OTHER` value was
rejected as leaking a report-specific concern into the ELO engine's own domain model,
which otherwise has no knowledge of report categories.

## Decision: `createdAt`/`updatedAt` are real columns, not JSON-metadata entries

**Decision**: Unlike most other entities in this codebase (which push timestamps into a
JSON `metaData` blob via `Metadata.META_DATA_KEY_CREATION_DATE`/`UPDATE_DATE`),
`ReportEntity.createdAt`/`updatedAt` are plain `long` columns, called out explicitly in
the entity's own class Javadoc.

**Rationale**: The rate-limit queries (`countByReporterHashAndCreatedAtGreaterThan`,
`countByCreatedAtGreaterThan`) need to filter on submission time directly at the database
level. Micronaut Data derives these queries from repository method names against real
entity fields — it cannot express a comparison against a value nested inside a JSON
column, so the rate limiter's correctness depends on this field being a real column, not
a stylistic choice independent of function.

**Alternatives considered**: Keeping timestamps in `metaData` for consistency with sibling
entities, and filtering rate-limit queries with a native/JSON-path query instead, was
rejected as needless complexity for a feature whose rate-limit path is meant to stay a
simple, auditable derived query.

## Decision: No `@Transactional` around `resolve()`'s multi-step side-effect chain

**Decision**: The punishment application, report update, up to two ELO-delta writes, and
event publish inside `resolve()` are four-to-five independent, uncoordinated writes with
no surrounding transaction.

**Rationale**: Not a preference — `@Transactional` is unusable project-wide due to the
same bean-ambiguity collision documented in `EloService`'s class Javadoc (constitution
Principle VI). Unlike `EloService`, this non-atomicity is **not** currently documented
anywhere in `ReportController` — this plan surfaces it (see `plan.md`'s Constitution
Check, Principle VI PARTIAL) as a gap in the existing code's self-documentation, not a
newly-discovered defect in its behavior.

**Alternatives considered**: None attempted in the shipped code. A Javadoc note mirroring
`EloService`'s is recommended as a low-risk follow-up independent of the larger
service-layer extraction (see `plan.md` Complexity Tracking).
