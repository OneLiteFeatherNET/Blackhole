# Feature Specification: Ban-Evasion Detection

**Feature Branch**: `004-evasion-detection`

**Created**: 2026-07-19

**Status**: Draft — retroactive specification of already-implemented behavior, written to
give this subsystem a baseline `spec.md` for `/speckit-plan`/`/speckit-tasks`/
`/speckit-converge` to build on later.

**Input**: User description: "Retroactive specification of ban-evasion detection via
hashed IP correlation"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Surface a signal when a banned player reconnects on a new account (Priority: P1)

A player who is currently banned creates or switches to a different account and connects
from the same IP address they always use. Network operators need to be alerted that two
seemingly-unrelated accounts share an IP address, so they can investigate and act,
without the system ever learning or storing that IP address itself.

**Why this priority**: This is the entire reason the feature exists — catching evasion is
worthless if the signal never surfaces, and every other behavior in this feature (what
gets recorded, how long it's kept) exists only in service of producing this signal
reliably.

**Independent Test**: Have two different player accounts connect from the same IP address
within the detection window and confirm a network-wide evasion signal is raised
identifying both accounts, without either account's actual IP address appearing anywhere
in that signal or in stored data.

**Acceptance Scenarios**:

1. **Given** two different player accounts have connected from the same IP address
   within the detection window, **When** the second account connects, **Then** the
   system raises an evasion signal identifying both accounts as sharing that IP address.
2. **Given** only one player account has ever connected from a given IP address, **When**
   that same account connects again, **Then** no evasion signal is raised.
3. **Given** an evasion signal is raised, **When** its contents are inspected, **Then**
   it contains only account identifiers already known to the system — never the
   underlying IP address itself.

---

### User Story 2 - Every login is checked, without exception, as a matter of course (Priority: P1)

Every player who connects to the network — banned or not, first-time or returning — has
their IP address checked against every other account recently seen on it, as a routine
part of logging in, so evasion is never missed simply because nobody thought to look.

**Why this priority**: A detection mechanism that only runs when someone remembers to
trigger it manually is not a detection mechanism — it has to be unconditional and
automatic to be trustworthy, which is why this ties with User Story 1 at the top
priority.

**Independent Test**: Log a normal player in and confirm a sighting is recorded and
checked for correlation even though nothing suspicious is happening, with no separate
step required to opt an account or IP address into being checked.

**Acceptance Scenarios**:

1. **Given** a player connects to the network, **When** the login completes, **Then** a
   sighting of that account on that IP address is recorded and checked for correlation
   with other accounts, regardless of whether that player has ever been punished.
2. **Given** the same account reconnects from the same IP address shortly after, **When**
   the login completes, **Then** the existing sighting's last-seen time is refreshed
   rather than a duplicate sighting being created.
3. **Given** the feature has not been configured with the secret needed to compute the
   IP-address signal, **When** a login occurs, **Then** no sighting is recorded and the
   system reports plainly that evasion detection is unavailable rather than silently
   proceeding with a weaker, guessable signal.

---

### User Story 3 - Correlation only reaches back a bounded window, and stops entirely once a signal is stale (Priority: P2)

An operator investigating evasion only cares about IP addresses shared *recently* — two
accounts that happened to share a household internet connection a year apart (e.g. that
IP address was reassigned long after the fact) is not a meaningful evasion signal, and
retaining that sighting data indefinitely would be an unjustified privacy cost.

**Why this priority**: This bounds both the usefulness (recent-only correlation is what
actually indicates evasion) and the privacy footprint (data has to eventually go away) of
the feature, but the feature still functions and produces value without a perfectly tuned
window — lower priority than the detection itself.

**Independent Test**: Have two accounts share an IP address separated by longer than the
detection window and confirm no signal is raised; separately, confirm a sighting older
than the retention window is deleted by routine housekeeping without manual intervention.

**Acceptance Scenarios**:

1. **Given** two accounts shared an IP address, but the second sighting happened after
   the detection window elapsed since the first, **When** the correlation check runs,
   **Then** no evasion signal is raised for that pair.
2. **Given** a sighting has not been refreshed in longer than the retention window,
   **When** the periodic housekeeping process next runs, **Then** that sighting is
   permanently deleted.
3. **Given** a sighting is well within the retention window, **When** the periodic
   housekeeping process runs, **Then** that sighting is left untouched.

### Edge Cases

- Three or more distinct accounts share the same IP address within the detection window:
  the evasion signal identifies all of them together, not just the two most recent.
- The same account reconnects from the same IP address many times in a row: this never by
  itself raises an evasion signal — a signal requires more than one distinct account
  sharing the IP address, never the same account's own repeat visits.
- A login is attempted while the feature's required configuration is entirely absent: the
  login itself still proceeds through the rest of the join flow; only the
  evasion-specific check is skipped, and the caller is told plainly why.
- An IP address cannot be attributed to any account yet (this is the very first sighting
  ever recorded for it): a new record is created and no signal is raised, since
  correlation requires at least two distinct accounts to compare.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST record a sighting linking a connecting account to the IP
  address it connected from, for every login, without requiring the account or IP address
  to be pre-flagged as suspicious.
- **FR-002**: System MUST derive the stored representation of an IP address through a
  one-way, secret-keyed transformation such that the original IP address can never be
  recovered from stored data, and such that guessing it by brute force is computationally
  infeasible even given the small size of the address space being hashed.
- **FR-003**: System MUST NOT store an IP address in any directly reversible form at any
  point in the detection flow.
- **FR-004**: System MUST, on every login, check whether more than one distinct account
  has been seen on the same IP address within a configurable recent time window, and MUST
  raise a network-wide evasion signal identifying every distinct account found sharing it
  when that condition is met.
- **FR-005**: System MUST NOT raise an evasion signal when only a single distinct account
  has been seen on an IP address within the detection window, no matter how many times
  that one account has reconnected.
- **FR-006**: System MUST refuse to record a sighting or perform correlation when its
  required secret configuration is absent, and MUST report that condition plainly to the
  caller rather than falling back to a weaker or unsalted signal.
- **FR-007**: System MUST refresh an existing sighting's last-seen time and occurrence
  count when the same account reconnects from an IP address it has already been seen on,
  rather than creating a duplicate record.
- **FR-008**: System MUST permanently delete a sighting once it has gone longer than a
  configurable retention period without being refreshed, on a recurring, unattended
  schedule.
- **FR-009**: System MUST make the detection window and the retention period each
  independently configurable per network deployment without a code change.
- **FR-010**: System MUST make the evasion signal available for other parts of the
  network's tooling to consume, without itself deciding what action, if any, is taken in
  response to that signal.

### Key Entities *(include if feature involves data)*

- **Correlation Sighting**: One record of a specific account having connected from a
  specific IP address — the IP address in its one-way transformed form, the account in
  its already-hashed form, when it was first and most recently seen, and how many times.
  Subject to its own bounded retention window as personal data in its own right.
- **Evasion Signal**: A point-in-time notice that more than one distinct account shares
  an IP address within the detection window — the transformed IP address and the set of
  distinct accounts found sharing it. Produced for other systems to act on; this feature
  does not itself decide or apply a consequence.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of logins produce a correlation check, with zero accounts or IP
  addresses exempted or requiring manual opt-in.
- **SC-002**: An evasion signal is raised within the same operation as the login that
  triggers it — no polling or batch delay between two accounts sharing an IP address and
  that fact becoming visible.
- **SC-003**: Zero raw IP addresses are recoverable from stored data at any time,
  including from the evasion signal itself.
- **SC-004**: A single account reconnecting from its own usual IP address any number of
  times produces zero false-positive evasion signals.
- **SC-005**: 100% of correlation sightings older than the configured retention period
  are removed by the next scheduled housekeeping run, with no operator action required.
- **SC-006**: The detection window and retention period can each be changed per
  deployment with zero redeploy or restart of any part of the system.

## Assumptions

- The account identifier this feature correlates against is already a hashed/tokenized
  reference by the time it reaches this feature, consistent with the project's
  GDPR-by-design constraint (constitution Principle I) — producing that hash is outside
  this feature's own requirements, matching the same assumption made in
  `specs/001-elo-engine/spec.md`.
- This feature deliberately stops at raising the evasion signal. Deciding whether a
  shared IP address actually warrants a punishment — and applying one — is the core
  punishment system's responsibility (`specs/002-punishment-core/spec.md`), not this
  feature's; nothing in this feature triggers a punishment or a standing change
  (`specs/001-elo-engine/spec.md`) directly. As of this writing no downstream consumer of
  the evasion signal exists yet in this deployment — it is produced for future
  moderator-facing tooling or automation to act on.
- "Network operators" is used as the reviewing/consuming actor throughout, since the
  project currently has no per-staff identity system (constitution Principle II); this
  specification does not assume one exists.
- The login flow that produces each sighting (an account connecting to the network) is an
  existing capability this feature depends on and hooks into, not something this feature
  itself defines.
- Retention and detection windows are expressed in whole days; sub-day precision is not a
  requirement this feature was built to satisfy.
