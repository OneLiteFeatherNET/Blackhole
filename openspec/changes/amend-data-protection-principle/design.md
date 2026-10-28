## Context

`openspec/config.yaml` holds the ratified principles. Principle 1 reads:

> GDPR-by-design, single-tenant. PII (IPs, raw player UUIDs) is hashed/tokenized wherever it is
> stored or published. No multi-tenancy, no tenantId discriminator; multiple networks = multiple
> deployments.

The principle is enforced by the specs in `openspec/specs/`. Several requirements there say that owners
are SHA-512 hashes, that IP addresses are stored only as keyed HMAC tokens, and that raw UUIDs and player
names are never stored. The planned work needs the opposite for some data:

- **IP bans** enforced by an OPNsense firewall alias need the clear-text address or CIDR range, because a
  firewall cannot match a keyed token.
- **Name history** needs every name a UUID logged in with.
- **Information gathering** (login and session data from Velocity: clear IP, ASN, country, names, client
  brand, protocol, session times, playtime) feeds alt detection, VPN detection and analytics. Those need the
  real address and the real identifier to correlate and to explain a result to a moderator.
- **Analytics lakehouse** (Parquet in Iceberg on Ceph RGW) needs identifiers that can be joined and erased.

A keyed hash can match two sightings but cannot produce an address or a name, so the hashed model cannot
serve these features. The team has decided to amend principle 1. This change records the amendment and the
rules that make it acceptable. It does not change code.

## Goals / Non-Goals

**Goals**

- Replace the text of principle 1 so that clear-text PII is allowed where a feature needs it, with a stated
  legal basis and stated safeguards.
- Keep the safeguards in force: retention, erasure, and no PII in published data.
- Make the existing hashing requirements explicit, so that nobody reads the amendment as silently
  invalidating them.
- Make the rules that the feature changes depend on available as one capability, `data-protection`.

**Non-Goals**

- No code, migration, API or client-spec change.
- No conversion of existing hashed data back to clear text.
- No change to single-tenancy, auth, layering, dual-ELO, rate limiting or the migration rules.
- No legal opinion. The legal wording is open for review (see Open questions).

## Decisions

### 1. Replace principle 1 with the following text (verbatim, to go into `openspec/config.yaml`)

```
  1. GDPR-by-design, single-tenant. One backend deployment serves one network. Personally
     identifiable data (IP addresses, raw player UUIDs, player names) MAY be stored in clear text
     where a feature needs it. The legal basis is legitimate interest (GDPR Art. 6(1)(f)) for
     network security and abuse prevention. Every store that holds such data MUST have a documented
     retention period enforced by a sweeper. Erasure on request MUST remain possible. Published data
     (public pages, events to third parties) MUST NOT carry IP addresses, raw UUIDs or player names.
     Existing hashes stay where they do not block a feature. No multi-tenancy, no tenantId
     discriminator; multiple networks = multiple deployments.
```

The old text said "hashed/tokenized wherever it is stored or published". The new text says the opposite
for storage, so the reader must be able to tell the change from the diff alone. The change carries a line
in its commit body that says so.

Alternatives considered:

- **Keep the hash rule and add exceptions per feature.** Rejected. Every feature would then argue its own
  exception and the principle would describe nothing. The team wants one rule for the platform.
- **Store clear text everywhere with no safeguards.** Rejected. It drops retention, erasure and the publish
  rule, which are what make the data defensible.
- **Use consent as the legal basis.** Rejected for the proposal. A ban that depends on the player's consent
  does not work for network security; legitimate interest was chosen. The final basis is for the qualified
  review to confirm.

### 2. Single-tenancy and the remaining principles are unchanged

Only the PII sentence and its safeguards change. The tenancy sentence stays, so no `tenantId` is introduced.
Principles 2 to 6 are not touched.

### 3. Existing specs stay valid; no spec is edited by this change

The amendment permits clear-text storage. It does not, by itself, remove the requirements below. Each one
stays in force until a later change modifies it. Feature changes that need clear text must modify the
relevant requirement in the same change that needs it.

Requirements in `openspec/specs/` that require hashing or forbid raw PII (checked on origin/main at
2026-10-28):

| Capability | Requirement | Conflicts with planned features? |
|---|---|---|
| `evasion-detection` | Keyed, non-reversible IP storage (HMAC-SHA512 token, raw IP never persisted) | Yes: IP bans, alt lookup and VPN detection need the clear address. Modified by the IP and information-gathering change. |
| `vanilla-import` | Player identity hashing (raw UUID and name not stored) | Yes if imported names or UUIDs must be kept for name history. |
| `vanilla-import` | IP bans are not imported | Yes: IP bans become a feature. Modified by the IP-ban change. |
| `proxy-enforcement` | Player identity hashing (owner hash used for lookups, Redis keys, sessions) | Yes for session data and name history. |
| `punishment-management` | Owners are hashed identifiers (raw UUID rejected as owner) | Yes if owners move to raw UUIDs. Keep until a change decides. |
| `punishment-evidence` | Evidence is pseudonymised (owner hashes only, display names not stored, UUID and IP strings rejected in payloads) | Yes for name history and IP evidence; the rule about published data stays. |
| `shared-cache` | Cache keys contain no raw PII (hashed owner keys) | Only if caches move to clear identifiers. |
| `elo-scoring` | Chat evidence stores only a hash | No. Chat evidence is not an IP or name store; keep. |
| `player-reports` | Reporter and reported identified by SHA-512 hash; malformed hash rejected | Only if reports move to names. Keep. |
| `player-resolution` | Lookup returns the owner hash | No. The owner hash remains a valid derived value. |
| `appeal-workflow` | `appellantHash` required and 128-character hex | No. Keep. |
| `admin-web-ui` | No personal data retained by the UI (browser storage) | Not a store: the UI rule still holds. Keep. |

Not a personal-data requirement: `staff-account-link` (link codes stored as hashes, which is a secret rule)
and `verified-actor` (staff anonymisation, which is already a retention rule).

Alternative considered: edit every hashing requirement now. Rejected. It would decide the IP, name and
owner-key designs before their own changes have been written. It would also make this change large and hard
to review, and it would mix a principle amendment with feature decisions.

### 4. New capability `data-protection` for the rules the amendment depends on

Feature changes must not each restate retention, erasure and the publish rule. This change adds one small
delta capability with three requirements:

- **Retention**: every store holding clear-text PII declares a retention period and a sweeper that deletes
  expired rows.
- **Erasure**: an operator can erase all PII of one player (UUID, names, IP-derived data) on request, and the
  erasure is audited.
- **No PII in published data**: public pages and events to third parties carry no IP address, UUID or player
  name.

Alternative considered: put the rules only in `config.yaml`. Rejected: principles are advisory text; a
capability has scenarios that a test can be written against.

`openspec validate --strict` refuses a change with no delta (checked 2026-10-28), so the capability is
required, not optional. It is also needed because the principle text refers to these rules.

### 5. The decision is recorded in ADR-BLKH-0004 in Outline

The ADR records the decision and its reasons. This change cites it. A search of Outline on 2026-10-28
did not find a document named ADR-BLKH-0004, so the ADR must be created or located before the code PR is
merged (see Open questions).

### 6. Code PR edits only the principle text and the CLAUDE.md overview

- `openspec/config.yaml` principle 1 is replaced with the text in decision 1.
- `CLAUDE.md`, in the overview paragraph that says PII is "hashed or tokenized wherever it's stored or
  published", is changed to match. Nothing else in `CLAUDE.md` changes.

## Risks / Trade-offs

- **Legal risk.** The amendment makes a legal claim (legitimate interest for storing IPs and names). If the
  claim is wrong, the consequences fall on the operator. Mitigation: the legal wording is an open question and
  nothing in the platform depends on this change until a qualified reviewer has read it.
- **Drift between principle and specs.** Until the feature changes land, the principle permits clear text
  while specs forbid it. Mitigation: decision 3 lists the conflicts, and each feature change must cite this
  change and modify the requirement it changes.
- **Erasure versus retained evidence.** Evidence and audit records may be kept under legal obligations, which
  can limit erasure. The `data-protection` erasure rule must say what is kept and why.
- **Retention periods are not decided here.** The principle requires a period for each store, but this change
  does not set numbers. Each feature change sets its own.
- **Downstream data leakage.** Clear-text data in new stores widens the blast radius of a breach. Mitigation
  sits in the feature changes (access roles, no published PII), not in this text.

## Migration Plan

No data migration. No Flyway migration. Existing hashes stay as they are. The code PR merges, the principle
reads the new text, and existing specs keep their requirements. Feature changes migrate their own data when
they need clear text, in their own proposals.

Rollback is a revert of the code PR, which restores the old principle text.

## Open Questions

1. **Legal review (owner: maintainer, reviewer to be named).** Someone qualified must confirm the legitimate
   interest basis, the wording of principle 1, and whether any retention period in the feature changes is
   acceptable. This is open and this change does not claim it is done.
2. **ADR-BLKH-0004.** No Outline document with this name was found on 2026-10-28. Confirm whether it exists
   under another title, or create it before the code PR merges.
3. **Scope of `data-protection` delta.** The capability is required by validation. If the maintainer prefers
   the rules in the feature changes only, a `skip_specs: true` change is the alternative, but that drops the
   retention and erasure rules from the spec tree.
4. **Governance of the code PR.** The global guidance says a code PR touches no file under `openspec/`, but
   this change's code PR must edit `openspec/config.yaml` because principle 1 lives there. The brief asks for
   this. Confirm the exception is acceptable.
