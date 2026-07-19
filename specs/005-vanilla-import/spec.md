# Feature Specification: Vanilla Ban-List Import

**Feature Branch**: `005-vanilla-import`

**Created**: 2026-07-19

**Status**: Draft — retroactive specification of already-implemented behavior, written to
give this subsystem a baseline `spec.md` for `/speckit-plan`/`/speckit-tasks`/
`/speckit-converge` to build on later.

**Input**: User description: "Retroactive specification of vanilla ban-list import support"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Migrate an existing vanilla ban list into Blackhole (Priority: P1)

A network operator switching a server from unmodified ("vanilla") Minecraft server
software to Blackhole already has a list of previously banned players sitting in that
server's own ban file. Those players must remain banned after the switch — the operator
should not have to look up and manually re-ban every one of them individually.

**Why this priority**: This is the entire purpose of the feature — without it, migrating
to Blackhole means either losing every existing ban or re-creating them all by hand,
which does not scale for any network with an established player base.

**Independent Test**: Submit a vanilla ban list containing entries for players with no
existing Blackhole punishment, and confirm each becomes an active, network-wide ban
attributed to a distinct import identity, with expiry semantics matching the source
entry.

**Acceptance Scenarios**:

1. **Given** a vanilla ban-list entry for a player with no existing punishment record in
   Blackhole, **When** it is imported, **Then** a network-wide ban is created for that
   player, attributed to an identity that is neither a human staff member nor the
   automatic standing system.
2. **Given** an entry whose recorded ban had already expired before the import ran (per
   its own expiry timestamp), **When** it is imported, **Then** it is recorded directly
   in that player's punishment history rather than becoming an active ban.
3. **Given** an entry with no expiry, or an expiry of "forever", **When** it is imported,
   **Then** a permanent ban is created.
4. **Given** an entry with a future expiry date, **When** it is imported, **Then** a
   temporary ban is created with a matching expiry.
5. **Given** an import has completed, **When** the operator reviews the result, **Then**
   they receive a count of how many entries were imported, skipped, and invalid.

---

### User Story 2 - Preview an import before committing it (Priority: P2)

Before running an import against production data, an operator wants to see what it
would do — how many entries would be imported, skipped, or rejected — without actually
creating any punishments, so mistakes (wrong file, unexpected overlap with existing
bans) can be caught first.

**Why this priority**: Bulk data operations against a live system are inherently risky;
a safe preview is what makes running the real import (US1) trustworthy rather than a
one-shot gamble. Ranked below US1 because the import itself is the capability that
delivers value — preview only makes it safer to use.

**Independent Test**: Submit a ban list in preview mode and confirm the reported counts
match what a real import of the same file would produce, while confirming nothing was
actually created or changed.

**Acceptance Scenarios**:

1. **Given** a valid ban list, **When** it is submitted in preview mode, **Then** the
   response reports the same imported/skipped/invalid counts a real import would
   produce, and no punishment or profile is created or changed.
2. **Given** a ban list was just previewed, **When** the same file is then submitted for
   a real import, **Then** the real outcome matches what the preview reported.

---

### User Story 3 - Already-punished players are not overwritten by an import (Priority: P2)

A player already has an active ban in Blackhole — issued after migration began, or by a
previous partial/repeated import run — that also happens to appear in the legacy ban
list being imported. The import must not clobber that existing decision.

**Why this priority**: Without this guarantee, importing (or re-importing) a file could
silently discard a punishment decision already made inside Blackhole, undermining trust
in the migration process and making the import unsafe to run more than once.

**Independent Test**: Import the same ban list twice in a row and confirm the second run
creates no new or duplicate punishments for players already imported by the first run.

**Acceptance Scenarios**:

1. **Given** a player already has an active ban recorded in Blackhole, **When** an
   import entry for that same player is processed, **Then** the existing active ban is
   left untouched and the entry is counted as skipped, not imported.
2. **Given** an entire ban list is imported a second time without changes, **When** the
   second run completes, **Then** every entry from the first run is reported as skipped
   and no new punishments exist for those players.

---

### User Story 4 - Malformed entries don't abort the whole import (Priority: P3)

A legacy ban list accumulated over years of manual edits and plugin quirks may contain a
handful of malformed records. An operator needs the rest of the file to import
successfully rather than the whole operation failing over one bad entry.

**Why this priority**: Real-world vanilla ban files are rarely perfectly formed; without
per-entry fault tolerance, a single bad record would block migration entirely until
someone manually cleans the source file — an avoidable obstacle to the feature's core
purpose (US1).

**Independent Test**: Submit a ban list containing entries with an unparseable player
identifier or expiry value alongside otherwise-valid entries, and confirm the valid
entries import successfully while each bad entry is individually reported.

**Acceptance Scenarios**:

1. **Given** an entry has a player identifier or expiry value that cannot be parsed,
   **When** the import runs, **Then** that entry is skipped and reported individually
   with enough detail to find it in the source file, while every other valid entry in
   the same file still imports.
2. **Given** an entry has no reason text, **When** it is imported, **Then** a default
   reason is used instead of the entry being rejected.
3. **Given** an entry has no creation timestamp, **When** it is imported, **Then** the
   current time is used instead of the entry being rejected.

---

### User Story 5 - IP ban entries are acknowledged, not silently dropped (Priority: P3)

Vanilla ban lists also track banned IP addresses in a separate file, but Blackhole has
no IP-level punishment concept. An operator who uploads both files needs to know their
IP ban data was seen and intentionally not carried over — not silently lost.

**Why this priority**: Silent data loss during a migration erodes trust in the whole
operation; this is a transparency safeguard rather than a core capability, so it ranks
below the behaviors that actually move ban data into the system.

**Independent Test**: Submit a ban list along with an IP ban list and confirm the result
reports how many IP ban entries were present and that none were imported.

**Acceptance Scenarios**:

1. **Given** an IP ban list is included in the import, **When** the import completes,
   **Then** the response reports how many IP ban entries were present and confirms that
   none of them were imported.
2. **Given** no IP ban list is provided, **When** the import completes, **Then** the
   result shows zero for that count rather than treating it as an error.

### Edge Cases

- The same player appears twice within one ban list: the second occurrence sees the
  punishment already created by the first occurrence within the same import run, and is
  counted as skipped rather than creating a duplicate.
- A ban list contains only IP ban entries, or an empty player ban list: the import still
  completes and returns a valid, zero-count summary rather than erroring.
- Two entries in the same file share identical reason text: they are treated as the same
  reason for reporting/grouping purposes rather than being tracked as unrelated reasons.
- An entry's player identifier is technically well-formed but has no other punishment
  history in Blackhole yet: a new punishment record is created for them, the same as
  any player's first-ever punishment elsewhere in the system.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST let a network operator submit a vanilla-format player ban list
  and process every entry into Blackhole's own punishment records in a single bulk
  operation.
- **FR-002**: System MUST convert each entry's raw player identifier into the same
  hashed representation used everywhere else in the system before storing or matching
  it, and MUST NOT persist the raw player identifier anywhere.
- **FR-003**: System MUST create every imported ban as a network-wide punishment, not a
  location- or chat-scoped one, matching a vanilla ban's own server-wide effect.
- **FR-004**: System MUST preserve each entry's original expiry semantics: no expiry or
  an expiry of "forever" becomes a permanent punishment; a future expiry date becomes a
  temporary punishment with a matching expiry; a past expiry becomes a punishment
  recorded directly into history as already inactive, never as a currently active ban.
- **FR-005**: System MUST attribute every punishment created by an import to a fixed,
  deterministic system identity that is distinct from any human staff member and from
  the automatic standing-based system, so an imported punishment remains distinguishable
  in the audit trail from both other kinds of issuer.
- **FR-006**: System MUST NOT import a punishment for a player who already has an active
  network-track punishment in Blackhole; such an entry MUST be counted as skipped rather
  than overwriting, duplicating, or otherwise altering the existing punishment.
- **FR-007**: System MUST reject only the individual entry, not the whole import, when
  an entry's player identifier or expiry value cannot be parsed, and MUST report each
  rejected entry with enough detail to identify it in the source file.
- **FR-008**: System MUST use a default reason for an entry that has no reason text, and
  MUST use the current time for an entry that has no creation timestamp, rather than
  rejecting either case.
- **FR-009**: System MUST support a preview mode that reports the same
  imported/skipped/invalid outcome counts a real import of the same data would produce,
  without creating or changing any punishment or profile.
- **FR-010**: System MUST accept an optional IP ban list alongside the player ban list,
  MUST report how many IP ban entries were present, and MUST NOT import any of them,
  since the system has no IP-level punishment concept; no raw IP address from that list
  may be persisted in any form.
- **FR-011**: System MUST NOT apply any automatic standing (ELO) adjustment as a result
  of an import, regardless of what standing effect a similarly-reasoned, manually-issued
  punishment might otherwise carry.
- **FR-012**: Every punishment created by an import MUST become observable through the
  same network-wide propagation and event mechanisms as any other newly created
  punishment, so enforcement points and dashboards treat it identically to a
  normally-issued punishment.
- **FR-013**: System MUST report, for every import (real or preview run), the total
  entries seen, how many were imported, how many were skipped as already-punished, and
  how many were invalid, with a human-readable detail per invalid entry.
- **FR-014**: The import capability MUST be reachable only by trusted operators/tools
  under the system's existing network-boundary trust model, and MUST NOT be exposed as a
  self-service or publicly documented capability.

### Key Entities *(include if feature involves data)*

- **Vanilla Ban List Entry**: One record from the source player ban file — raw player
  identifier, display name, a staff-supplied source label, creation time, optional
  expiry, optional reason — exactly as recorded by unmodified server software.
- **Vanilla IP Ban Entry**: One record from the source IP ban file — counted for
  transparency only; never converted into a Blackhole punishment or persisted in any
  form.
- **Import Outcome**: A summary of one import operation (real or preview) — total
  entries seen, how many were imported, skipped as already-punished, and invalid, a
  human-readable detail per invalid entry, and how many IP ban entries were present.
- **Import System Identity**: The single, fixed, non-human issuer identity attributed to
  every punishment created by this feature, distinct from any staff member and from the
  automatic standing system.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator can migrate an existing vanilla ban list of any size into
  Blackhole in one operation, with zero hand-created individual punishments required.
- **SC-002**: 100% of players who already have an active punishment in Blackhole are
  left unchanged by an import that also lists them, with zero duplicate or conflicting
  punishments created — including when the same file is imported more than once.
- **SC-003**: An operator can preview the exact outcome of an import (imported/skipped/
  invalid counts) before committing it, with zero side effects from the preview itself.
- **SC-004**: A single malformed entry never prevents any other valid entry in the same
  file from importing successfully.
- **SC-005**: 100% of punishments created via import are attributable to one
  identifiable import-system source, distinguishable from human staff members and from
  the automatic standing system in every case.
- **SC-006**: No raw player identifier or raw IP address from the source data is
  retained in stored punishment/profile data once an import completes.
- **SC-007**: An operator is told exactly how many IP ban entries in the source data
  were not imported, with zero silent data loss.

## Assumptions

- "Network operator" is used as the actor throughout, consistent with
  `specs/001-elo-engine/spec.md` and `specs/002-punishment-core/spec.md`, since the
  project currently has no per-staff identity system (constitution Principle II); this
  spec does not assume one exists.
- This feature creates punishments and punishment profiles using the same underlying
  entities, active/history rotation semantics, and network-wide propagation/event
  mechanisms that `specs/002-punishment-core/spec.md` specifies for punishments applied
  through the normal path — this spec does not redefine those mechanics, only how import
  entries are turned into them.
- The "system identity" this feature attributes imported punishments to (FR-005) is a
  third, distinct kind of issuer alongside a human staff member and the automatic
  standing system described in `specs/001-elo-engine/spec.md` — none of the three are
  ever confused with one another in the audit trail.
- A vanilla ban list's own "source" field (the banning operator's display name as
  recorded by the source server) is not treated as a verifiable staff identity, since
  the project has no per-staff identity system to validate it against; it is not
  preserved as the punishment's issuer.
- Import is assumed to be run by an operator with direct file access to the legacy
  server's ban files, not by end users; this spec does not define a self-service import
  path.

