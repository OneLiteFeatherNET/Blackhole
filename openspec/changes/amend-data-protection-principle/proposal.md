## Why

Blackhole's first principle says that personally identifiable data (IP addresses, raw player UUIDs)
is hashed or tokenized wherever it is stored or published. That rule was written for a ban list that
only needed to recognise a player. The features the team now wants need the real data: IP bans enforced
at the firewall need clear-text addresses, name history needs the names a UUID has logged in with, alt
lookup and VPN detection need the address behind a login, and an analytics lakehouse needs raw
identifiers to answer questions at all. A keyed hash can match two sightings, but it cannot be turned back
into an address for a firewall rule or a name for a staff member. Principle 1 as written therefore blocks
these features, and the team has decided to amend it rather than work around it.

The amendment keeps the parts of the principle that protect people: a stated legal basis, a retention
period for every store that holds such data, erasure on request, and a hard rule that published data never
carries these identifiers. It changes only what may be stored, and only where a feature needs it.

## What Changes

- **Principle 1 of `openspec/config.yaml` (`context:`) is amended.** Clear-text IP addresses, raw player
  UUIDs and player names MAY be stored where a feature needs them. The legal basis is legitimate interest
  (GDPR Art. 6(1)(f)) for network security and abuse prevention. Every store that holds such data has a
  documented retention period enforced by a sweeper. Erasure on request stays possible. Published data
  (public pages, events to third parties) never carries IP addresses, UUIDs or player names. Existing
  hashes stay where they do not block a feature. The new text is written out verbatim in `design.md`,
  decision 1.
- **`CLAUDE.md` wording** that describes principle 1 (the overview paragraph says PII is "hashed or
  tokenized wherever it's stored or published") is aligned with the amended principle, so the two
  documents do not disagree.
- **No runtime behaviour changes.** No code, no migration, no API, no client spec and no existing
  hash is converted back to clear text by this change.
- **Existing specs are not edited here.** Specs that require hashing stay valid until a later change
  modifies them (see `design.md`, decision 3).

## Capabilities

### New Capabilities

- `data-protection`: the cross-cutting rules that the amended principle depends on: a documented retention
  period and sweeper for every store holding clear-text personal data, erasure on request, and the rule that
  published data carries no IP address, UUID or player name. Feature changes (IP bans, name history, the
  information-gathering store) reference these rules rather than restating them.

### Modified Capabilities

- None. The existing capability specs in `openspec/specs/` are not modified by this change.

## Impact

- **`openspec/config.yaml`**: principle 1 text in the `context:` block (code PR).
- **`CLAUDE.md`**: the sentence about hashing PII in the project overview (code PR).
- **Specs**: none modified. Specs that require hashing keep their requirements until a later change
  modifies them; the list is in `design.md`, decision 3.
- **Code, database, API, generated client, Velocity plugin, web panel**: no impact.
- **Downstream changes**: the information-gathering and IP-ban changes depend on this change being merged
  first. They must cite `amend-data-protection-principle` as a prerequisite.

## Out of scope

- Any code change. The code PR edits only `openspec/config.yaml` and `CLAUDE.md`.
- Migrating existing hashes (owner hashes, IP tokens, reporter and appellant hashes) back to clear text.
  Each feature that needs clear text decides its own migration in its own change.
- Writing the legal basis assessment or the record of processing. The legal wording of principle 1 needs
  review by someone qualified (see `design.md`, open questions); this change does not claim the review is done.
- Changing the existing specs' hashing requirements. Those are changed by the feature changes that need it.
- The ADR. ADR-BLKH-0004 in Outline is cited as the record of this decision; this change does not create it.

Ships as: `docs(openspec): amend the data protection principle`
