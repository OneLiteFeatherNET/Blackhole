# Feature Specification: Dual-ELO Punishment Engine

**Feature Branch**: `001-elo-engine`

**Created**: 2026-07-19

**Status**: Draft — retroactive specification of already-implemented behavior, written to
give the existing dual-ELO engine a baseline `spec.md`/`plan.md`/`tasks.md` for
`/speckit-converge` to check the code against going forward.

**Input**: User description: "Retroactive specification of the existing dual-ELO
punishment engine: chat and gameplay ELO tracks, automatic soft/hard threshold
punishments, decay, and chat-toxicity scoring"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Automatic punishment without a moderator online (Priority: P1)

A player repeatedly breaks the rules — through toxic chat, cheating, or actioned player
reports — while no moderator is online to review and act. The network still needs to
protect other players immediately rather than waiting for a human to notice.

**Why this priority**: This is the entire reason the dual-ELO system exists per the
project's charter — punishment must not depend on staff availability.

**Independent Test**: Drive a player's chat or gameplay standing down past the
configured soft threshold through any combination of signals, and confirm a temporary
punishment is applied immediately with no moderator action, and that it is attributed
to the system rather than a person.

**Acceptance Scenarios**:

1. **Given** a player's chat standing is at or above the soft threshold, **When** a
   signal (toxic chat, actioned report, manual adjustment) pushes it below the soft
   threshold, **Then** a temporary chat punishment is applied automatically and recorded
   as system-issued.
2. **Given** a player's gameplay standing is at or above the soft threshold, **When** an
   anticheat signal pushes it below the soft threshold, **Then** a temporary gameplay
   punishment is applied automatically, independent of that player's chat standing.
3. **Given** a player's standing on either track drops from at/above the hard threshold
   to below it, **When** a permanent punishment has been configured for that track,
   **Then** a permanent punishment is applied automatically.
4. **Given** a player's standing on either track drops below the hard threshold,
   **When** no permanent punishment has been configured for that track yet, **Then** no
   automatic ban is applied, and the crossing is still visible to network operators so
   they notice the missing configuration.
5. **Given** a player is already below the soft threshold, **When** a further violation
   occurs while they remain below it, **Then** no duplicate automatic punishment is
   applied for that same threshold — only a fresh downward crossing triggers one.

---

### User Story 2 - Chat is screened for toxicity automatically (Priority: P1)

Players send a constant stream of chat that no moderator can read in real time. The
network needs toxic messages to affect the sender's standing immediately, without
storing the offending message text in a way that could leak later.

**Why this priority**: Chat toxicity is one of the two primary signal sources feeding
the automatic-punishment story (US1) and has its own data-handling constraint (never
retaining raw message text) that is independently testable.

**Independent Test**: Submit a chat message known to score above the flag threshold and
confirm the sender's chat standing decreases proportionally to the severity, evidence
sufficient to review the decision is retained, and the original message text is not
retrievable afterward.

**Acceptance Scenarios**:

1. **Given** a chat message scores below the flag threshold, **When** it is evaluated,
   **Then** the sender's chat standing is unaffected and nothing is recorded as
   evidence.
2. **Given** a chat message scores at or above the flag threshold, **When** it is
   evaluated, **Then** the sender's chat standing decreases by an amount proportional to
   the severity score, and a reviewable evidence record is created without storing the
   raw message text.
3. **Given** a network operator wants a different definition of "toxic" than the
   built-in placeholder scoring, **When** they configure or replace the scoring logic,
   **Then** flagged messages continue to feed into the chat standing exactly as before.

---

### User Story 3 - Standing recovers when a player stops violating rules (Priority: P2)

A player who received a penalty and then plays cleanly (or simply stops playing) should
not be permanently marked down forever — the system should let a below-baseline standing
recover over time, whether or not the player is still active.

**Why this priority**: Without recovery, an old or one-off violation would permanently
suppress a player's standing and eventually push everyone below the automatic-punishment
thresholds regardless of recent behavior — this is what keeps thresholds meaningful over
time. Lower priority than US1/US2 because it doesn't gate the primary protective
behavior, only its long-term fairness.

**Independent Test**: Lower a player's standing below baseline, let the configured
recovery interval elapse with no further violations, and confirm the standing moves back
toward baseline (but never past it) — including for a player who never reconnects.

**Acceptance Scenarios**:

1. **Given** a player's standing on a track is below baseline, **When** the configured
   recovery interval elapses with no further violations, **Then** their standing moves
   toward baseline by the configured recovery amount.
2. **Given** a player's standing has recovered to exactly baseline, **When** further time
   passes with no violations, **Then** the standing does not rise above baseline through
   recovery alone.
3. **Given** a player stops playing entirely (no further activity of any kind), **When**
   enough time has passed for recovery to be owed, **Then** the recovery is still applied
   without requiring the player to reconnect or trigger any other signal.

---

### User Story 4 - Network operators can review standing and history (Priority: P2)

A network operator investigating a player, or auditing why the system took a particular
automatic action, needs to see that player's current standing on both tracks and the
full chronological history of what changed it and why.

**Why this priority**: Automatic punishment (US1) only stays trustworthy if its
decisions are inspectable after the fact; this is the review capability that makes the
rest of the system auditable rather than a black box. Not P1 because the punishment
itself already happens without this — this story is about oversight, not the protective
behavior itself.

**Independent Test**: Query a player's current standing and their score-change history
independently, and confirm every automatic and manual change appears with its cause and
resulting score.

**Acceptance Scenarios**:

1. **Given** a player has a standing on file, **When** a network operator requests their
   current standing, **Then** both track scores are returned.
2. **Given** a player has no standing on file yet, **When** a network operator requests
   their current standing, **Then** the system clearly indicates none exists rather than
   returning a fabricated baseline value.
3. **Given** a player has accumulated score changes from multiple sources over time,
   **When** a network operator requests their history, **Then** every change is returned
   in order with its cause, amount, and resulting score.

### Edge Cases

- A single adjustment is large enough to cross both the soft and hard thresholds at
  once: only the more severe (hard-tier) automatic consequence is applied, not both.
- The hard threshold is crossed with no permanent punishment configured: no automatic
  ban happens, but the crossing is still recorded and observable so operators notice the
  gap (see US1 Acceptance Scenario 4).
- A score adjustment arrives for a player with no existing standing on either track: a
  new standing is created starting from baseline before the adjustment is applied.
- Two adjustments to the same player/track happen back to back, both crossing the same
  threshold: only the first genuine downward crossing triggers an automatic punishment
  (see US1 Acceptance Scenario 5) — the second is recorded but does not re-trigger it.
- A chat message is blank/empty: it is treated as non-toxic and does not affect standing.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST maintain two independent behavior standings per player — one
  for chat conduct, one for gameplay conduct — each starting from a configurable
  baseline value when first created.
- **FR-002**: System MUST evaluate every submitted chat message for toxicity and, when
  it is flagged, reduce the sender's chat standing by an amount proportional to the
  severity of the flag.
- **FR-003**: System MUST NOT retain the raw text of a chat message once toxicity
  evaluation completes; only a non-reversible, review-sufficient reference to a flagged
  message may be retained, and only for a configurable retention period.
- **FR-004**: System MUST accept standing adjustments from any signal source (chat
  toxicity, anticheat signals, actioned player reports, manual staff adjustment, or
  scheduled recovery) through the same evaluation path, so no adjustment source can
  bypass the threshold check in FR-005/FR-006.
- **FR-005**: System MUST automatically apply a temporary punishment to a player the
  moment either standing drops from at/above a configurable "soft" threshold to below
  it.
- **FR-006**: System MUST automatically apply a permanent punishment the moment either
  standing drops from at/above a configurable "hard" threshold to below it, but only if
  network operators have configured what that permanent punishment consists of for that
  track; if none is configured, System MUST skip the automatic ban rather than invent
  one, and MUST still make the crossing observable to operators (see FR-011).
- **FR-007**: System MUST NOT re-trigger a soft or hard automatic punishment for a
  player whose standing is already below that threshold — only a fresh downward
  crossing (previously at/above, now below) triggers a new automatic punishment.
- **FR-008**: Every automatically-applied punishment MUST be attributable in the audit
  trail to the system itself, distinguishably different from a punishment issued by a
  human staff member or created via a data import.
- **FR-009**: System MUST permanently record every standing change — its source/cause,
  signed amount, resulting standing, and timestamp — regardless of whether the change
  was automatic or manual.
- **FR-010**: A standing that is below baseline MUST recover toward baseline over time
  at a configurable daily rate when no further violations occur, and MUST NOT recover
  past baseline through this recovery alone.
- **FR-011**: Standing recovery (FR-010) MUST occur for every player with a below-
  baseline standing regardless of whether that player has any further activity —
  recovery must not depend on the player reconnecting or triggering another signal.
- **FR-012**: Network operators MUST be able to retrieve a player's current standing on
  both tracks, and MUST be able to retrieve that player's complete standing-change
  history in chronological order.
- **FR-013**: Baseline value, soft/hard threshold values, automatic punishment
  durations, and recovery rate/interval MUST each be independently configurable per
  network deployment without a code change.
- **FR-014**: The chat-toxicity scoring logic MUST be replaceable by network operators
  without changing how a flagged message's severity feeds into the chat standing (FR-002
  continues to hold under any scoring implementation).

### Key Entities *(include if feature involves data)*

- **ELO Profile**: A player's current standing — one score per track (chat, gameplay),
  each with its own last-changed timestamp — keyed to a hashed player reference, never a
  raw identifier.
- **ELO Event**: One immutable audit-trail record of a single standing change: which
  track, the signed amount applied, why it happened, the resulting standing, when it
  happened, and an optional link to supporting evidence.
- **Chat Evidence**: A time-limited, non-reversible reference created when a chat
  message is flagged — sufficient to support reviewing why a standing changed, without
  ever holding the original message text.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every time a player's standing crosses a configured soft threshold
  downward, a temporary punishment is in effect within the same operation that produced
  the crossing — zero manual steps and zero delay attributable to staff availability.
- **SC-002**: Every time a player's standing crosses a configured hard threshold
  downward with a permanent punishment configured for that track, a permanent punishment
  is in effect within the same operation — zero manual steps.
- **SC-003**: No original chat message text is recoverable from stored data once that
  message has been evaluated for toxicity.
- **SC-004**: A player who stops violating rules returns to their baseline standing
  within a predictable, operator-configured number of days on both tracks, whether or
  not they remain active on the network.
- **SC-005**: 100% of standing changes — automatic and manual — are visible to network
  operators in a complete, correctly-ordered per-player history with cause and resulting
  score.
- **SC-006**: A player who remains below a threshold across multiple subsequent
  violations receives at most one automatic punishment for that threshold crossing, not
  one per violation.

## Assumptions

- The player reference ("owner") used throughout this feature is already a hashed/
  tokenized identifier by the time it reaches this feature, consistent with the
  project's GDPR-by-design constraint — producing that hash is outside this feature's
  own requirements.
- Actually enforcing a punishment once applied (e.g. preventing chat or gameplay access)
  is a separate, existing capability this feature depends on and triggers, not something
  this feature itself defines.
- The default chat-toxicity scoring logic is an intentionally basic placeholder;
  producing a production-grade toxicity classifier is out of scope for this
  specification (see FR-014, which only requires that scoring be replaceable).
- The chat and gameplay tracks are assumed fully independent — a change to one never
  affects the other.
- "Network operators" is used as the reviewing/configuring actor throughout, since the
  project currently has no per-staff identity system; this specification does not assume
  one exists.
