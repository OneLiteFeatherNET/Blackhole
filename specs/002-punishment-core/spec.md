# Feature Specification: Punishment Core

**Feature Branch**: `002-punishment-core`

**Created**: 2026-07-19

**Status**: Draft — retroactive specification of already-implemented behavior, written
to give the core punishment system a baseline `spec.md`/`plan.md`/`tasks.md` for
`/speckit-converge` to check the code against going forward. This is the shared
chokepoint the dual-ELO engine (see `specs/001-elo-engine/`), appeals, reports, and
evasion detection all apply punishments through — those features assume, but do not
redefine, the guarantees specified here.

**Input**: User description: "Retroactive specification of the core punishment system:
applying punishments from reusable templates, per-track active-punishment slots with
history rotation, automatic expiry, manual revocation, and network-wide propagation to
enforcement points"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Apply a punishment that takes effect everywhere immediately (Priority: P1)

Staff, or an automated system elsewhere in the network, decides a player needs to be
punished and applies a predefined punishment template to them. That punishment must be
enforced consistently by every server/proxy on the network right away, without anyone
having to configure each enforcement point separately.

**Why this priority**: This is the fundamental capability every other punishment-related
feature (dual-ELO, appeals, reports, evasion, imports) depends on — nothing else in the
system can protect players until a punishment, once decided, actually takes effect
everywhere.

**Independent Test**: Apply a template-based punishment to a player and confirm it
becomes observable to an enforcement point (a proxy/server) within a short, bounded
delay, without any manual per-location setup step.

**Acceptance Scenarios**:

1. **Given** a punishment template exists, **When** it is applied to a player, **Then**
   a new punishment is created for that player and becomes observable network-wide
   within a bounded delay.
2. **Given** a punishment template has a configured duration, **When** it is applied,
   **Then** the resulting punishment carries a matching expiry; **given** it has none,
   **Then** the resulting punishment is permanent.
3. **Given** the referenced template no longer exists, **When** an application is
   attempted, **Then** no punishment is created and the failure is reported clearly.

---

### User Story 2 - A player never carries two active punishments on the same track (Priority: P1)

A player who already has an active punishment (e.g. an existing temporary ban) commits
another violation and receives a new punishment on the same enforcement track. The
system must not end up enforcing two conflicting punishments on that track at once, and
must not silently lose the earlier one.

**Why this priority**: Without this guarantee, "what punishment is currently in effect"
becomes ambiguous, which undermines every downstream consumer (enforcement points,
appeals, dashboards) that needs a single source of truth for a player's current state.

**Independent Test**: Apply two punishments on the same track to the same player in
sequence and confirm only the second is active afterward, with the first fully
retrievable in that player's history.

**Acceptance Scenarios**:

1. **Given** a player has an active punishment on a track, **When** a new punishment on
   that same track is applied, **Then** the previous one is moved into history and the
   new one becomes the sole active punishment for that track.
2. **Given** a player has an active punishment on one track, **When** a punishment is
   applied on the *other* track, **Then** the first punishment is unaffected — the two
   tracks are independent.
3. **Given** a player has no active punishment on a track, **When** one is applied,
   **Then** it simply becomes active with nothing to rotate into history.

---

### User Story 3 - A temporary punishment lifts itself automatically (Priority: P2)

A player received a temporary punishment. Once its duration has elapsed, it must stop
being enforced without requiring staff to remember to come back and lift it.

**Why this priority**: Temporary punishments are only meaningful if "temporary" is
honored automatically — otherwise every temporary punishment silently becomes permanent
unless someone manually tracks and reverses it, which does not scale and contradicts
the punishment's own stated duration.

**Independent Test**: Apply a punishment with a short duration, wait past its expiry,
and confirm it is no longer treated as active and has moved into history — both for a
system checking it directly and for the network-wide propagated state.

**Acceptance Scenarios**:

1. **Given** an active punishment has an expiry in the past, **When** anything evaluates
   whether it's currently active, **Then** it is treated as not active, even before any
   periodic housekeeping has run.
2. **Given** an active punishment's expiry has just passed, **When** the periodic
   housekeeping process next runs, **Then** it is moved into history and the network-wide
   propagated state is updated to reflect it's no longer active.
3. **Given** a punishment has no expiry (permanent), **When** any amount of time passes,
   **Then** it is never automatically treated as expired.

---

### User Story 4 - Staff can lift a punishment early (Priority: P2)

Staff determine that an active punishment should end before its natural expiry (or that
it was permanent and should now be lifted) and revoke it directly.

**Why this priority**: Automatic enforcement (US1-US3) must remain correctable by a
human decision — a system that could only ever apply and never lift punishments early
would be unusable in practice (mistaken punishments, successful appeals, changed
circumstances).

**Independent Test**: Revoke an active punishment and confirm it immediately stops being
treated as active and is recorded as revoked (not merely expired) in history, including
network-wide.

**Acceptance Scenarios**:

1. **Given** a player has an active punishment on a track, **When** it is revoked,
   **Then** it moves into history marked as revoked (not expired), and stops being
   enforced network-wide within the same bounded delay as a new punishment taking
   effect.
2. **Given** a player has no active punishment on the track being revoked, **When** a
   revocation is attempted, **Then** nothing changes and the failure is reported clearly
   rather than silently succeeding.

---

### User Story 5 - Network operators maintain reusable punishment templates (Priority: P3)

Network operators define and adjust the set of reasons/durations/kinds punishments can
be applied as, independent of applying any specific punishment.

**Why this priority**: Templates are what make US1 usable at scale (consistent reasons
and durations instead of ad-hoc values every time), but the template catalog itself can
be managed separately from — and less frequently than — actually applying punishments,
so it's lower priority than the enforcement behaviors above.

**Independent Test**: Create, update, and remove a punishment template independently of
applying it to any player, and confirm the catalog reflects each change immediately.

**Acceptance Scenarios**:

1. **Given** no template exists yet for a given reason, **When** an operator creates
   one, **Then** it becomes available to apply to players.
2. **Given** a template exists, **When** an operator updates its reason, kind, or
   duration, **Then** future applications use the new values; already-applied
   punishments created from the old values are unaffected.
3. **Given** a template exists, **When** an operator removes it, **Then** it is no
   longer available to apply, without affecting punishments already created from it.

### Edge Cases

- A punishment is applied to a player who has never received one before: a new,
  otherwise-empty enforcement record is created for them rather than the application
  failing for "profile not found."
- Two enforcement points (proxies) observe a punishment's state at slightly different
  times due to network-wide propagation delay: each still converges to the correct
  state shortly after, and can fall back to asking the source of truth directly if its
  local copy is stale or missing.
- A punishment template is configured to also affect a player's automatic-standing
  score (see the dual-ELO engine spec) and the punishment being applied was itself
  triggered *by* that same standing system: the standing adjustment must not be applied
  a second time in this case (no double-dipping / feedback loop).
- Applying a punishment when a template's duration value is malformed or missing where
  a duration is expected: the application must fail clearly rather than silently
  producing a punishment with an incorrect or undefined expiry.
- A revoke is requested for a track that has already naturally expired moments earlier:
  treated the same as "no active punishment on that track" (see US4 Acceptance Scenario
  2), not an error distinct from that case.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST let a punishment be applied to a player by selecting a
  predefined, reusable template rather than specifying every detail ad hoc each time.
- **FR-002**: System MUST support punishments that restrict a player from: a single
  specific location within the network, the entire network, or chat only — as
  independent options a template selects between.
- **FR-003**: System MUST support both permanent punishments (no expiry) and temporary
  punishments (an expiry derived from the applied template's configured duration).
- **FR-004**: System MUST enforce at most one active punishment per player per
  enforcement track (chat vs. everything else) — applying a new one on a track that
  already has an active punishment MUST move the previous one into that player's
  history rather than running both simultaneously or discarding the earlier one.
- **FR-005**: System MUST record, for every punishment, who or what issued it, in a way
  that lets a system-issued punishment be distinguished from a punishment issued by any
  other party.
- **FR-006**: System MUST make every newly applied, revoked, or naturally expired
  punishment observable to every enforcement point network-wide within a short, bounded
  delay, without requiring each enforcement point to query the source of truth for
  every single check.
- **FR-007**: System MUST automatically treat a temporary punishment as no longer active
  once its expiry has passed, both for any direct evaluation of that punishment and for
  the network-wide propagated state, without requiring a person to manually lift it.
- **FR-008**: System MUST let an authorized party revoke an active punishment before its
  natural expiry, on request, and that revocation MUST become observable network-wide
  within the same bounded delay guaranteed by FR-006.
- **FR-009**: System MUST retain every punishment — whether currently active, expired,
  or revoked — as part of a player's permanent history; a punishment is never deleted
  merely because it stopped being active.
- **FR-010**: System MUST let network operators create, update, and remove reusable
  punishment templates independently of applying any specific punishment; updating or
  removing a template MUST NOT retroactively alter punishments already created from it.
- **FR-011**: System MUST support attaching supporting evidence to a punishment when
  available, while never retaining more of any underlying raw content (e.g. chat
  message text) than a hash and/or external reference sufficient to review the
  decision, plus a bounded retention window.
- **FR-012**: If a punishment application references a template that does not exist,
  System MUST reject it clearly rather than creating an incomplete or ambiguous
  punishment.
- **FR-013**: If a revocation is requested for a player/track with no active
  punishment, System MUST report that clearly rather than silently succeeding or
  creating a punishment.

### Key Entities *(include if feature involves data)*

- **Punishment Template**: A reusable, named definition — reason, punishment kind,
  optional duration, optional effect on a player's automatic-standing score — that a
  punishment is created from. Exists independently of any player.
- **Punishment**: One concrete, applied instance: who/what issued it, which kind it was
  applied as, when it was created, when (if ever) it expires, which template produced
  it, and any attached evidence. Immutable in substance once created, aside from being
  marked revoked.
- **Punishment Profile**: A player's current enforcement state — at most one active
  punishment per track (general-access, chat), plus the complete, ordered history of
  every punishment that has ever left an active slot (by expiry, revocation, or
  supersession).
- **Punishment Evidence**: A record supporting why a punishment was issued — an
  external reference and/or content hash tied to one punishment, with a bounded
  retention window; never the raw underlying content itself.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A punishment applied from a template is observable to every enforcement
  point within a few seconds (pushed proactively, not discovered by polling), with zero
  per-location manual configuration.
- **SC-002**: A player's active-punishment count per track never exceeds one at any
  point in time; a superseded punishment is always still retrievable afterward.
- **SC-003**: A temporary punishment is treated as no longer active immediately by
  anything that evaluates its expiry directly, and is reflected as inactive in the
  network-wide propagated state within at most 1 minute of its configured expiry.
- **SC-004**: 100% of manual revocations become network-wide observable within the same
  few-seconds window as SC-001.
- **SC-005**: 100% of a player's punishments — active, expired, and revoked — remain
  retrievable in their history indefinitely (subject only to the project's general data
  retention posture, not a punishment-specific deletion).
- **SC-006**: Punishment templates can be created, changed, or removed with zero
  redeploy or restart of any part of the system.

## Assumptions

- "Authorized party" for applying/revoking punishments is any caller able to reach the
  API at all — this project has no per-staff identity or permission system yet
  (constitution Principle II); this spec does not assume one exists.
- The network-wide propagation mechanism to enforcement points is best-effort and
  eventually consistent by design (a bounded but non-zero delay, with a documented
  fallback of asking the source of truth directly on a local cache miss) — this is an
  accepted design property, not a defect to eliminate.
- This feature's interaction with the automatic dual-ELO standing system (a template
  optionally nudging a player's standing when applied) is a dependency this feature
  triggers into; the standing system's own rules are specified separately (see
  `specs/001-elo-engine/spec.md`), not redefined here.
- Evidence retention/erasure follows the same bounded-window, hash-only pattern already
  established elsewhere in the system (e.g. chat evidence for the dual-ELO engine); this
  spec does not introduce a new or different retention policy.
