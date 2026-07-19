# Feature Specification: Player Reports

**Feature Branch**: `006-player-reports`

**Created**: 2026-07-19

**Status**: Draft — retroactive specification of already-implemented behavior, written to
give this subsystem a baseline `spec.md` for `/speckit-plan`/`/speckit-tasks`/
`/speckit-converge` to build on later. Cross-reference `specs/001-elo-engine/spec.md` for
how an actioned report's standing effect behaves once applied.

**Input**: User description: "Retroactive specification of the player reporting system
feeding into the dual-ELO engine"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A player reports misbehavior without being able to spam the system (Priority: P1)

A player witnesses another player breaking the rules — abusive chat, cheating, griefing,
or something that doesn't fit those categories — and submits a report describing what
happened, optionally attaching evidence and a short description. The network needs to
receive that report for review while preventing reporting itself from becoming a way to
flood the system.

**Why this priority**: Reports are the entry point for the whole subsystem — nothing
downstream (review, resolution, standing effects) exists without a report first being
accepted. The abuse guard is inseparable from that acceptance path because unlimited
submission would make the feature itself a griefing vector.

**Independent Test**: Submit a report with a valid category and confirm it is accepted,
stored with an open status, and retrievable afterward; then submit enough additional
reports from the same reporter within a short window to confirm further submissions are
rejected until the window passes.

**Acceptance Scenarios**:

1. **Given** a player has witnessed rule-breaking, **When** they submit a report with a
   category, the reported player, and optionally a description, evidence references, and
   extra metadata, **Then** the report is accepted and stored in an open, unresolved
   state with a submission timestamp.
2. **Given** a reporter has already submitted their configured maximum number of reports
   within the current time window, **When** they submit another report, **Then** the
   submission is rejected as rate-limited rather than stored.
3. **Given** the network as a whole has already received its configured maximum number of
   reports within the current time window, **When** any player submits another report,
   **Then** the submission is rejected as rate-limited even if that specific reporter is
   still under their own individual limit.
4. **Given** a report is successfully submitted, **When** it is accepted, **Then** its
   acceptance is announced network-wide so other interested systems can react to a new
   report existing.

---

### User Story 2 - A reviewer resolves a report and, when warranted, punishes the reported player in the same step (Priority: P1)

A network operator reviewing an open report decides what should happen to it — dismiss
it, mark it actioned, or otherwise close it out — and, when the report is upheld, applies
a punishment to the reported player as part of that same decision rather than as a
separate disconnected step.

**Why this priority**: Resolving reports is the other half of the feature's core loop
and is what actually protects the network — a report that is never resolved has no
effect. Folding punishment application into resolution is the subsystem's real
integration point with the punishment system, not an optional add-on.

**Independent Test**: Resolve an open report as actioned while supplying a punishment
template and confirm both the report's resolution and the resulting punishment on the
reported player are visible afterward; separately, resolve a report without supplying a
punishment template and confirm the report resolves with no punishment side effect.

**Acceptance Scenarios**:

1. **Given** an open report exists, **When** a reviewer resolves it with a status, an
   optional note, and their identity, **Then** the report's status, resolution note, and
   resolver are recorded along with an updated timestamp.
2. **Given** a reviewer resolving a report also supplies a punishment template to apply,
   **When** the resolution is submitted, **Then** the punishment is applied to the
   reported player before the report's resolution is finalized.
3. **Given** a reviewer supplies a punishment template to apply but does not identify who
   is applying it, **When** the resolution is submitted, **Then** the resolution is
   rejected and neither the report nor any punishment is changed.
4. **Given** a reviewer supplies a punishment template that no longer exists, **When**
   the resolution is submitted, **Then** the resolution fails, no punishment is applied,
   and the report's status is left unchanged.
5. **Given** a report identifier that does not correspond to any report, **When** a
   resolution is attempted against it, **Then** the attempt fails clearly and nothing is
   changed.
6. **Given** a report is resolved, **When** the resolution completes, **Then** it is
   announced network-wide with the report's identifier, the reported player, the
   resulting status, and who resolved it.

---

### User Story 3 - An actioned report moves the reported player's standing, and rewards an accurate reporter (Priority: P2)

When a reviewer resolves a report as actioned, the reported player's behavioral standing
should reflect that a legitimate complaint was upheld against them. Separately, when the
report demonstrably led to a real punishment (not merely a status change), the reporter
who flagged it accurately should be recognized for it.

**Why this priority**: This is the subsystem's payoff — it's what makes reports feed the
automatic-punishment system described in `specs/001-elo-engine/spec.md` rather than being
a review queue with no teeth. It is P2 rather than P1 because resolution (US2) is
functional and independently valuable even before this consequence is layered on.

**Independent Test**: Resolve a report as actioned for each category and confirm the
reported player's matching standing track decreases by the configured amount; separately,
resolve a report as actioned with a punishment template applied and confirm the
reporter's standing on that same track increases, and resolve one as actioned *without* a
punishment template and confirm the reporter's standing is unaffected.

**Acceptance Scenarios**:

1. **Given** a report about abusive chat is resolved as actioned, **When** the resolution
   completes, **Then** the reported player's chat standing decreases by the configured
   amount, attributed to the report.
2. **Given** a report about cheating or griefing is resolved as actioned, **When** the
   resolution completes, **Then** the reported player's gameplay standing decreases by
   the configured amount, attributed to the report.
3. **Given** a report categorized as not fitting any specific behavior track is resolved
   as actioned, **When** the resolution completes, **Then** no standing change occurs for
   that report, since it doesn't map to either behavioral track.
4. **Given** an actioned report's resolution also applied a punishment template to the
   reported player, **When** the resolution completes, **Then** the reporter's standing
   on the same track the reported player was docked on increases by the configured reward
   amount.
5. **Given** a report is resolved as actioned but no punishment template was applied as
   part of that resolution, **When** the resolution completes, **Then** the reporter's
   standing is unaffected — marking a report actioned alone does not earn a reward.
6. **Given** a report is resolved with any status other than actioned (for example
   dismissed), **When** the resolution completes, **Then** neither the reported player's
   nor the reporter's standing changes as a result of that report.

---

### User Story 4 - Network operators can review the full report queue (Priority: P3)

A network operator wants to see what reports exist across the network — open, under
review, actioned, or dismissed — to triage what needs attention or audit past decisions.

**Why this priority**: Without visibility into the report queue, resolution (US2) can
only happen if a reviewer already somehow knows a report's identifier out of band. Lower
priority than submission/resolution/standing-effects because it's an oversight
capability, not one of the two actions that actually change system state.

**Independent Test**: Submit several reports and confirm they can all be retrieved as a
paginated collection afterward, including their current status and any resolution detail
already recorded.

**Acceptance Scenarios**:

1. **Given** any number of reports exist across all statuses, **When** a network operator
   requests the report collection, **Then** they receive them back page by page with
   each report's full current detail.
2. **Given** no reports exist yet, **When** a network operator requests the report
   collection, **Then** they receive an empty result rather than an error.

### Edge Cases

- A report is submitted with no description, no evidence references, and no extra
  metadata: it is still accepted — only the reporter, the reported player, and a category
  are required.
- A report is resolved with a status other than actioned (e.g. moved from open to under
  review, or dismissed): the resolution is recorded, but no standing change occurs and no
  punishment is implied.
- The same report is resolved more than once: each resolution overwrites the previous
  status, note, resolver, and timestamp; a report actioned a second time can trigger the
  standing effect again, since the system does not track whether a given report already
  produced one.
- A resolution both supplies a punishment template and results in a status other than
  actioned: the punishment is still applied (punishment application is driven by the
  template being present, not by the resulting status), but no standing effect occurs
  since that requires the actioned status specifically.
- A reporter and a reported player are the same identity: nothing in submission prevents
  a self-report; it is accepted like any other.
- Two reports reference the same reported player: each is resolved independently, and
  each actioned resolution applies its own standing effect — they are not merged or
  deduplicated.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow a player to submit a report identifying who they are
  reporting, the category of behavior it concerns, and optionally a bounded free-text
  description, references to supporting evidence, and extensible metadata.
- **FR-002**: System MUST support at minimum the following report categories: abusive
  chat, cheating, griefing, and a catch-all for behavior that doesn't fit those.
- **FR-003**: System MUST reject a submission that is missing the reporter, the reported
  player, or a category, rather than accepting an incomplete report.
- **FR-004**: A newly submitted report MUST start in an open, unresolved state with no
  resolution detail, and MUST record when it was submitted.
- **FR-005**: System MUST limit how many reports a single reporter can submit within a
  configurable rolling time window, rejecting submissions beyond that limit until the
  window rolls forward.
- **FR-006**: System MUST additionally limit how many reports the network as a whole can
  receive within the same configurable rolling time window, independent of and enforced
  regardless of the per-reporter limit, as a backstop against a reporter's identity being
  varied to evade FR-005.
- **FR-007**: System MUST announce a report's submission network-wide so other interested
  parts of the system can react to it.
- **FR-008**: System MUST allow a network operator to retrieve the full collection of
  reports, across all statuses, as a paginated result.
- **FR-009**: System MUST allow a network operator to resolve an existing report by
  setting its outcome status, an optional resolution note, and the identity of who
  resolved it, and MUST record when that resolution happened.
- **FR-010**: System MUST reject a resolution attempt against a report identifier that
  does not exist, without side effects.
- **FR-011**: System MUST allow a resolution to optionally apply an existing punishment
  template to the reported player as part of the same resolution, rather than requiring a
  separate follow-up action.
- **FR-012**: System MUST require the identity of who is applying it whenever a
  resolution specifies a punishment template to apply, and MUST reject the resolution
  without applying anything if that identity is missing.
- **FR-013**: System MUST reject a resolution that references a punishment template that
  no longer exists, leaving the report's status and the reported player's punishments
  unchanged.
- **FR-014**: When a report is resolved with an actioned outcome and its category maps to
  a behavioral standing track, System MUST reduce the reported player's standing on that
  track by a configurable amount, attributing the change to the report (see
  `specs/001-elo-engine/spec.md` for how a standing reduction is evaluated once applied,
  including automatic-punishment threshold effects).
- **FR-015**: System MUST NOT apply a standing change for a report whose category does
  not map to a behavioral track, even when that report is resolved as actioned.
- **FR-016**: System MUST NOT apply any standing change — to either the reported player
  or the reporter — for a resolution outcome other than actioned.
- **FR-017**: When a report is resolved as actioned and that resolution also applied a
  punishment template to the reported player, System MUST additionally increase the
  reporter's standing on the same track by a configurable reward amount, attributing the
  change to the report.
- **FR-018**: System MUST NOT reward the reporter when a report is resolved as actioned
  without a punishment template having been applied as part of that same resolution —
  marking a report actioned alone is not sufficient.
- **FR-019**: System MUST announce a report's resolution network-wide, including the
  report's identity, the reported player, the resulting status, and who resolved it.
- **FR-020**: The per-reporter and network-wide submission limits, their rolling time
  window, the actioned-report standing penalty, and the accurate-report reward amount
  MUST each be independently configurable per network deployment without a code change.

### Key Entities *(include if feature involves data)*

- **Report**: A single complaint about a player's behavior — who reported it, who it's
  about, its category, an optional description and evidence references, its current
  lifecycle status, when it was submitted and last updated, and, once resolved, who
  resolved it and any resolution note. Both the reporter and the reported player are
  identified only by a hashed reference, never a raw identifier.
- **Report Category**: The kind of behavior a report concerns; determines which
  behavioral standing track (if any) an actioned resolution affects.
- **Report Status**: The lifecycle state of a report — open, under review, actioned, or
  dismissed — set by a reviewer during resolution.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A reporter can never have more than their configured per-window submission
  limit of reports accepted within any rolling window, regardless of how many they
  attempt.
- **SC-002**: The network as a whole can never have more than its configured per-window
  submission limit of reports accepted within any rolling window, regardless of how many
  distinct reporters attempt to submit.
- **SC-003**: 100% of reports resolved as actioned with a category that maps to a
  behavioral track produce a standing reduction on the reported player within the same
  resolution operation — zero separate manual steps.
- **SC-004**: 100% of actioned resolutions that also applied a punishment template
  produce a standing increase for the reporter within the same resolution operation, and
  0% of actioned resolutions without an applied punishment template do.
- **SC-005**: Every submitted report and every resolution is retrievable afterward with
  its full current detail, across an unbounded number of pages.
- **SC-006**: A resolution that references a punishment template that no longer exists
  never partially completes — no punishment is applied, and the report's status is
  never touched.

## Assumptions

- The reporter and reported player references arriving at this feature are already
  hashed, consistent with the project's GDPR-by-design constraint (see
  `.specify/memory/constitution.md`, Principle I) — producing that hash is outside this
  feature's own requirements.
- Per Principle II (no auth layer, no per-actor identity), the reporter's identity, the
  resolving reviewer's identity, and the identity applying a punishment are all supplied
  by the caller and are not independently verified against who is actually making the
  request. This specification does not assume a per-actor identity system exists.
- The standing effects described in User Story 3 (FR-014, FR-017) are evaluated by the
  dual-ELO engine specified in `specs/001-elo-engine/spec.md`; this specification only
  covers when and how a report triggers that evaluation, not how the engine itself
  applies thresholds, decay, or automatic punishments once triggered.
- Applying a punishment template as part of resolution (FR-011–FR-013) is provided by the
  core punishment system specified in `specs/002-punishment-core/spec.md`; this
  specification only covers when a report resolution invokes that capability, not how
  templates or active-punishment slots themselves work.
- There is no enforced state-machine over report status — a report can be moved to any
  status from any prior status, including being resolved more than once. This reflects
  actual current behavior; whether that should be constrained is out of scope for this
  retroactive specification.
- Evidence references attached to a report are stored as opaque identifiers pointing at
  existing evidence records; this feature does not itself validate that a referenced
  evidence entry exists.
- "Network operator" is used throughout as the reviewing/resolving actor, consistent with
  the sibling specs, since the project currently has no per-staff identity system.
