# Feature Specification: Appeal Workflow

**Feature Branch**: `003-appeal-workflow`

**Created**: 2026-07-19

**Status**: Draft — retroactive specification of already-implemented behavior, written to
give this subsystem a baseline `spec.md` for `/speckit-plan`/`/speckit-tasks`/
`/speckit-converge` to build on later. Cross-reference `specs/002-punishment-core/spec.md`
for how the punishment revocation this feature triggers actually behaves — this spec
assumes, but does not redefine, those guarantees.

**Input**: User description: "Retroactive specification of the appeal workflow:
contesting automatic and manual punishments, eligibility, wait periods, decision outcomes"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A punished player contests a punishment (Priority: P1)

A player who has received a punishment — whether applied automatically by the standing
system or manually by a staff member — believes it should be lifted or shortened, and
submits an appeal with a written statement explaining why.

**Why this priority**: This is the entry point for the entire feature — without a way to
submit an appeal, none of the eligibility gating, wait periods, or review decisions have
anything to act on.

**Independent Test**: Submit an appeal against an existing punishment with a statement,
and confirm it is recorded and immediately shows whether it passed or failed the
eligibility checklist, without requiring a human to look at it first.

**Acceptance Scenarios**:

1. **Given** a punishment exists, **When** a player submits an appeal against it with a
   statement, **Then** the appeal is recorded and immediately evaluated against the
   eligibility checklist, ending up either awaiting human review or marked ineligible.
2. **Given** a punishment identifier that does not correspond to any real punishment,
   **When** an appeal is submitted against it, **Then** the appeal is rejected rather than
   recorded.
3. **Given** an appeal is submitted, **When** the eligibility evaluation completes,
   **Then** the full checklist result (every factor considered and its outcome) is stored
   with the appeal for later audit, not just the final eligible/ineligible verdict.

---

### User Story 2 - Appeals are gated by a fixed, auditable checklist rather than free-form staff judgment (Priority: P1)

Before any human ever looks at an appeal, it must clear an objective, consistently-applied
checklist: enough time has passed since the punishment was issued, and the player hasn't
just had a similar appeal denied or ruled ineligible days ago.

**Why this priority**: This is what keeps the appeal process fair and resistant to gaming
— without an objective gate, review outcomes would depend entirely on which staff member
happens to look at an appeal, and players could flood the queue with repeat attempts.

**Independent Test**: Appeal a punishment before its minimum wait period has elapsed and
confirm it is marked ineligible without human involvement; appeal again after a prior
denial within the cooldown window and confirm the same.

**Acceptance Scenarios**:

1. **Given** a punishment was issued automatically by the standing system, **When** an
   appeal against it is submitted before that punishment's shorter automatic-punishment
   wait period has elapsed, **Then** the appeal is marked ineligible for review.
2. **Given** a punishment was issued manually (by staff or any non-system source),
   **When** an appeal against it is submitted before that punishment's longer manual
   wait period has elapsed, **Then** the appeal is marked ineligible for review.
3. **Given** a prior appeal against the same punishment was denied or ruled ineligible
   within the configured cooldown window, **When** a new appeal against that same
   punishment is submitted, **Then** it is marked ineligible regardless of how much time
   has passed since the punishment itself was issued.
4. **Given** the minimum wait period has elapsed and there is no recent denied/ineligible
   appeal against the same punishment, **When** an appeal is submitted, **Then** it
   becomes eligible and awaits human review.

---

### User Story 3 - A network operator reviews an eligible appeal and decides its outcome (Priority: P1)

A network operator who did not issue the punishment being appealed reviews an eligible
appeal and either grants a full lift, grants a shorter new expiry, or denies it.

**Why this priority**: The eligibility gate (User Story 2) only decides whether an appeal
*can* be reviewed — a human decision is still what actually reverses or reduces a
punishment, which is the entire point of an appeal process existing.

**Independent Test**: Review an eligible appeal with each of the three possible decisions
and confirm the punishment's enforcement state changes accordingly (or doesn't, for a
denial), and that the decision is recorded on the appeal with who made it.

**Acceptance Scenarios**:

1. **Given** an eligible appeal is awaiting review, **When** a reviewer grants a full
   lift, **Then** the underlying punishment stops being enforced immediately, the same as
   any other early revocation.
2. **Given** an eligible appeal is awaiting review, **When** a reviewer grants a duration
   reduction with a new, sooner expiry, **Then** the punishment remains in effect until
   that new expiry instead of its original one.
3. **Given** an eligible appeal is awaiting review, **When** a reviewer denies it,
   **Then** the punishment is left completely unchanged.
4. **Given** any of the three decisions is recorded, **When** it is applied, **Then** the
   appeal itself records who decided it, any explanatory note, and the final outcome for
   later audit.
5. **Given** an appeal is not currently awaiting review (already decided, or never became
   eligible), **When** a review decision is attempted against it, **Then** it is rejected
   rather than silently re-applied.
6. **Given** the same party who issued the punishment attempts to review the appeal
   against it, **When** the review is attempted, **Then** it is rejected — a punishment's
   issuer may not decide their own appeal.

---

### User Story 4 - The most severe punishments can only ever be shortened, never fully lifted, through an appeal (Priority: P2)

A permanent, network-wide punishment is treated as categorically more serious than any
other punishment kind — an appeal against one can win a shorter (but still non-zero)
duration, never a complete, immediate lift.

**Why this priority**: This is a deliberate safety ceiling on how far a single review
decision can reverse the network's most severe consequence — important for trust in the
system, but it only matters once User Story 3's basic review path already works.

**Independent Test**: Attempt to grant a full lift against an eligible appeal for a
permanent, network-wide punishment and confirm it is rejected, while a duration
reduction against the same appeal succeeds.

**Acceptance Scenarios**:

1. **Given** an eligible appeal against a permanent, network-wide punishment, **When** a
   reviewer attempts to grant a full lift, **Then** the decision is rejected and the
   punishment remains fully in effect.
2. **Given** an eligible appeal against a permanent, network-wide punishment, **When** a
   reviewer grants a duration reduction instead, **Then** it succeeds and the punishment
   now has a bounded, sooner expiry.
3. **Given** an eligible appeal against any punishment that is not a permanent,
   network-wide one, **When** a reviewer grants a full lift, **Then** it succeeds without
   this restriction applying.

---

### User Story 5 - Reviewers see a supporting good-faith signal drawn from the player's standing (Priority: P3)

When a reviewer looks at an appeal, they can see whether the player's standing on the
track that did *not* trigger this particular punishment has recovered back to baseline,
as one extra piece of context to weigh alongside the appeal statement.

**Why this priority**: This is supporting context that makes review decisions more
informed, not a requirement that gates whether an appeal can be reviewed at all — lower
priority than the hard gates and the decision path itself, since the review flow (User
Stories 3-4) is fully functional without it.

**Independent Test**: Submit an appeal for a player whose non-triggering standing has
recovered to baseline and one for a player whose has not, and confirm the checklist
stored with each appeal reflects that difference for a reviewer to see.

**Acceptance Scenarios**:

1. **Given** a punishment's triggering cause is known, **When** an appeal against it is
   evaluated, **Then** the checklist records whether the *other* standing track has
   recovered to baseline, without that fact affecting whether the appeal is eligible.
2. **Given** a punishment's triggering cause is not recorded (e.g. it was issued directly
   by a person rather than the automatic standing system), **When** an appeal against it
   is evaluated, **Then** the checklist still records a best-effort supporting standing
   signal, inferred from what kind of punishment it is.

### Edge Cases

- A player submits more than one appeal against the same punishment while an earlier one
  is still awaiting review: nothing blocks the new submission outright — the checklist's
  repeat-appeal cooldown only looks at *denied or ineligible* prior appeals, not ones
  still pending.
- A punishment is fully lifted or naturally expires between when an appeal against it
  became eligible and when a reviewer acts on it: the review decision is rejected as
  having nothing left to grant, and the appeal is left in its prior state rather than
  being marked resolved.
- A duration-reduction decision is submitted without a new expiry, or with one that is
  not in the future: the decision is rejected outright.
- A reviewer submits a decision that isn't one of the three valid outcomes (full lift,
  duration reduction, denial): the decision is rejected.
- The player's standing on the supporting track has no record on file at all (never
  established): it is treated as already at baseline for the purposes of the supporting
  signal, not as a missing/unknown value.
- An appeal against a punishment with an unusual or missing creation timestamp cannot be
  reliably time-gated; this is not expected to occur since every punishment records its
  creation time as part of being issued (see `specs/002-punishment-core/spec.md`).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST let a player submit an appeal against a specific punishment,
  consisting of a hashed identity of the appellant and a written statement, without
  requiring a person to review it before it is recorded.
- **FR-002**: System MUST reject an appeal submission that references a punishment that
  does not exist.
- **FR-003**: System MUST immediately evaluate every submitted appeal against a fixed
  eligibility checklist and record whether it passed, before any human reviews it.
- **FR-004**: System MUST require a minimum amount of time to have elapsed since a
  punishment was issued before an appeal against it can be eligible, and MUST use a
  shorter minimum wait for punishments issued automatically by the standing system than
  for punishments issued any other way.
- **FR-005**: System MUST make an appeal ineligible if a prior appeal against the same
  punishment was denied or ruled ineligible within a configurable cooldown window,
  regardless of how much time has passed since the punishment itself was issued.
- **FR-006**: System MUST record the complete checklist outcome — every factor evaluated
  and its result, not only the final eligible/ineligible verdict — on the appeal itself,
  and MUST version that checklist so that a future change to its rules never
  retroactively reinterprets an appeal already evaluated under an earlier version.
- **FR-007**: System MUST classify a permanent, network-wide punishment as the most
  severe punishment tier, distinct from every other punishment kind and duration.
- **FR-008**: System MUST only allow an eligible appeal to be decided by a reviewer as
  one of exactly three outcomes: a full lift, a duration reduction to a specified future
  expiry, or a denial — any other requested outcome MUST be rejected.
- **FR-009**: System MUST reject a full-lift decision against an appeal for a punishment
  classified as the most severe tier (FR-007) — such an appeal may only ever receive a
  duration reduction or a denial.
- **FR-010**: System MUST require a specific, future expiry timestamp when a duration
  reduction is granted, and MUST reject the decision if none is given or if it is not in
  the future.
- **FR-011**: System MUST reject a review decision made by the same party that issued the
  punishment being appealed.
- **FR-012**: System MUST only accept a review decision against an appeal that is
  currently awaiting review; a decision against an appeal in any other state MUST be
  rejected.
- **FR-013**: When a full lift is granted, System MUST stop enforcing the underlying
  punishment immediately, following the same revocation guarantees as any other early
  lift (see `specs/002-punishment-core/spec.md`).
- **FR-014**: When a duration reduction is granted, System MUST keep the underlying
  punishment in effect only until the new, sooner expiry rather than its original one.
- **FR-015**: When a denial is recorded, System MUST leave the underlying punishment
  completely unchanged.
- **FR-016**: If the underlying punishment is no longer active (already lifted or
  naturally expired) by the time a review decision is submitted, System MUST reject the
  decision as having nothing left to grant, rather than applying it or silently
  succeeding.
- **FR-017**: System MUST record, for every decided appeal, who decided it, any
  explanatory note they gave, and the final outcome.
- **FR-018**: System MUST provide reviewers a supporting signal — whether the player's
  standing on the track that did not trigger the appealed punishment has recovered to
  its baseline — as informational context that does not itself affect eligibility or
  gate any decision.
- **FR-019**: System MUST let network operators retrieve the full list of submitted
  appeals.

### Key Entities *(include if feature involves data)*

- **Appeal**: A single contest of one punishment by its (hashed) appellant: a written
  statement, its current lifecycle state, the full eligibility checklist result it was
  evaluated against, and — once decided — who decided it, their note, and the outcome.
  Always tied to exactly one punishment.
- **Eligibility Checklist Result**: The versioned, point-in-time snapshot produced when
  an appeal is submitted — minimum wait period required and whether it was met, whether
  this is a disallowed repeat appeal, the punishment's severity tier, and the supporting
  standing signal. Stored verbatim on the appeal so later checklist changes cannot
  retroactively alter what an already-submitted appeal was judged against.
- **Review Decision**: The outcome a reviewer records against an eligible appeal — full
  lift, duration reduction (with its new expiry), or denial — together with who made it
  and an optional note.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of appeal submissions against a nonexistent punishment are rejected
  before any appeal record is created.
- **SC-002**: 100% of appeals are evaluated against the eligibility checklist at
  submission time with zero human involvement required to reach an eligible/ineligible
  verdict.
- **SC-003**: An appeal against an automatically-issued punishment never requires a
  longer minimum wait than the same punishment would require if it had been issued
  manually — the automatic path is never slower to appeal.
- **SC-004**: 100% of review decisions against a permanent, network-wide punishment that
  attempt a full lift are rejected; 100% of duration-reduction attempts against the same
  punishments succeed when a valid future expiry is given.
- **SC-005**: 100% of review decisions by the punishment's own issuer are rejected before
  any change to the punishment's enforcement state.
- **SC-006**: 100% of granted full lifts stop the underlying punishment from being
  enforced within the same bounded delay guaranteed for any other revocation (see
  `specs/002-punishment-core/spec.md`, SC-004).
- **SC-007**: 100% of decided appeals retain a permanent record of who decided them, the
  outcome, and the checklist they were judged eligible or ineligible against.

## Assumptions

- "Reviewer" and "network operator" are used as the deciding actor throughout, and
  "the same party who issued the punishment" is compared using whatever client-supplied
  identifier the punishment and the review decision both carry — this project has no
  per-staff identity or permission system yet (constitution Principle II), so this
  self-review check is a best-effort comparison of unverified, caller-supplied values,
  not a verified identity check.
- The appellant's hashed identity is assumed to already be a properly hashed/tokenized
  value by the time it reaches this feature, consistent with the project's GDPR-by-design
  constraint; producing that hash is outside this feature's own requirements.
- This feature's revocation and duration-reduction mechanics reuse the same active-
  punishment-slot and history rules defined for the core punishment system (see
  `specs/002-punishment-core/spec.md`) rather than redefining them — an appeal is a
  distinct trigger into that system's existing revoke/reduce behavior, not a parallel
  implementation of punishment lifecycle rules.
- The supporting standing signal (User Story 5) depends on the dual-ELO engine's
  standing/baseline concept (see `specs/001-elo-engine/spec.md`); this spec treats that
  as an existing input it reads, not something it defines.
- A player with no standing on file yet for the supporting track is treated as already at
  baseline for this feature's purposes — a deliberately permissive default so an
  appellant who has never triggered that track is never penalized by the supporting
  signal for lacking history.
- There is no maximum number of appeal attempts a player may submit against the same
  punishment; the minimum-wait and repeat-appeal-cooldown rules (FR-004, FR-005) are the
  only throttle, not a hard submission limit.
