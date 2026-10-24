# Design

## Context

Observed in the code on `main` (2026-10-24):

- `PlayerClientBrandListener` already sends the brand to `PUT /profile/{owner}/session` (dashboard
  enrichment, failures ignored); `PlayerLoginListener` sends the protocol version and the raw IP to
  `POST /evasion/record`, where `IpCorrelationService` stores only HMAC-SHA512(ip, salt) per owner
  hash, flags two owners on one token inside a window and publishes `evasion.detected`, and
  `IpCorrelationRetentionSweeper` deletes old rows. Detection is off when the salt is unset.
- Chat mutes use `PlayerChatEvent` denial, gated by `MuteChatDenial` on SignedVelocity being loaded
  (`@Dependency(id = "signedvelocity", optional = true)`), because Velocity kicks a 1.19.1+ client
  whose signed message a plugin cancels. There is no `CommandExecuteEvent` usage yet.
- `ServerConnectListener` denies `ServerPreConnectEvent` using `ServerResolver.facts(serverName)` and
  the pure `ServerTargets.covers`; `DriverCloudNetView` is the only class importing CloudNet.
- Sections (`SectionSpec`, `ServerTargetKind`) and templates are ADMIN-written, `VIEWER`/`PROXY`
  readable, mirrored to the proxy through the shared cache with a change notifier. The CloudNet
  catalogue is a proxy-reported snapshot in the shared cache with a TTL (`PROXY` writes, `VIEWER+`
  reads).
- Reports reach staff in game through `ReportNotifier` over a Redis pub/sub channel; the notice
  carries names and is never stored.
- `config.yaml` principles bind: GDPR by design, Entra roles with a verified actor, layered backend,
  automatic ELO punishments stay contestable, infra owns rate limiting, Flyway append-only,
  `@Transactional` unusable.

## Goals / Non-Goals

Goals:
- Staff define rules over brand, mods, channels, protocol version and commands in the panel; the
  proxy enforces them globally or per section and server.
- The effect of a hit is chosen per rule: notify, deny, or apply a template.
- A hashed client fingerprint strengthens ban-evasion recognition.
- No raw client trait is ever stored against an owner.

Non-Goals: see the proposal's out-of-scope list. In particular no attempt to make the signal
unforgeable.

## What is detectable, and how reliable (verified 2026-10-24)

Sources: Velocity API javadoc 4.1.0 / 3.5.0 (`Player`, `PlayerClientBrandEvent`,
`PlayerChannelRegisterEvent`, `PlayerModInfoEvent`, `CommandExecuteEvent`,
`CommandExecuteEvent.CommandResult`, `CommandExecuteEvent.SignedState`,
`PlayerAvailableCommandsEvent`), the SignedVelocity README, and the Fabric networking API change
that moved channel registration to the configuration phase (FabricMC/fabric#3244). The repository
builds against Velocity `4.3.0` (`velocityVersion` in `velocity/build.gradle.kts`); the events below
exist since 3.x and are re-checked against 4.3.0 in the first implementation task.

| Trait | Source in Velocity | What it tells | Honest limits |
|-------|--------------------|---------------|---------------|
| Brand | `Player#getClientBrand()` (`@Nullable`), `PlayerClientBrandEvent#getBrand()` | `vanilla`, `fabric`, `neoforge`, `forge`, `lunarclient:...`, other clients | Free text the client chooses. A mod can send `vanilla`, another string or no brand at all (the `brandpacket` Fabric mod does exactly that). Arrives after login, during or after the configuration phase, so it may not be known at `LoginEvent` or the first `ServerPreConnectEvent`. |
| Registered channels | `PlayerChannelRegisterEvent#getChannels()` (fires for each `minecraft:register`; Velocity does not wait for it) | Mods that register a custom payload channel (`fabric:...`, `neoforge:...`, a cheat client's own) | Client-controlled and spoofable. Fabric API mods can leak their presence by registering channels even when the brand is hidden, but a determined client can drop that too. Some mods register nothing. Registration can happen mid-session. |
| Legacy mod list | `Player#getModInfo()` (`Optional<ModInfo>`), `PlayerModInfoEvent` | Forge mod ids and versions | Only the legacy FML handshake (Forge up to 1.12) sends this to a proxy. Modern Forge, NeoForge and Fabric negotiate their mods in the configuration phase with the backend; Velocity does not expose a mod list for them. Empty is the normal case today. |
| Protocol version | `InboundConnection#getProtocolVersion()` | The wire protocol the client speaks | Reliable for what the proxy can talk to, but a version-translation proxy in front (ViaProxy and similar) makes a newer client look older. |
| Command line | `CommandExecuteEvent#getCommand()` (fires before the proxy handles or forwards the command; `CommandResult.denied()` stops both) | The first word typed | Namespaced forms (`plugin:cmd`) and backend aliases are different strings. A command the proxy cannot see (typed through a plugin message or a book/sign exploit) is out of reach. |

Consequence stated plainly: **brand, channels and mod list are client-controlled and spoofable.**
A hit is evidence that this client announced the trait, not that the player runs a cheat. A miss
proves nothing. Rules on these traits are a deterrent against careless users and a signal for staff;
only protocol and command rules act on facts the proxy itself observed. This is why the default
action is `NOTIFY` and why `TEMPLATE` on a client-controlled signal requires an acknowledgement
(decision 5).

## Decisions

### 1. One rule model, five match kinds, evaluated on the proxy

A rule is `{key, displayName, kind, patterns[], protocolMin, protocolMax, negate, scope, action,
denyMessage, templateKey, acknowledgedSpoofable, hideCommand, enabled}`.

- `kind` is `BRAND | MOD | CHANNEL | PROTOCOL | COMMAND`. The service validates that only the fields
  of that kind are set (a sealed `ClientRuleDTO` per resource with `CreateRequest`, `UpdateRequest`,
  `Response`, `Error`; the service answers an expected failure with the `Error` variant).
- Patterns are case-insensitive globs (`*`, `?`), normalised to lower case, 1 to 32 per rule, each at
  most 128 characters, `[a-z0-9._:*?/@+-]` only. Rule keys are `[a-z0-9-]{1,64}`, unique.
- `PROTOCOL` holds `protocolMin <= protocolMax` (Velocity protocol numbers); `negate` makes it an
  allowlist ("hit when outside"). `negate` is also allowed for `BRAND`: hit when the brand is known
  and matches none of the patterns. A client that never sends a brand is not hit by a negated brand
  rule (see limits).
- Evaluation is a **pure function on the proxy** (`RuleEngine.evaluate(traits, scopeFacts, rules)`),
  so enforcement needs no backend round trip and keeps working when the backend is down. The proxy
  holds the enabled rules in memory, loaded at start and reloaded when the change notifier fires
  (same mechanism as sections and templates). The backend never evaluates rules; it stores them and
  acts on reported hits (decision 6).
- A rule table, not a script: no regex (ReDoS), no expressions.

### 2. Scope reuses sections and CloudNet targeting

`scope` is `GLOBAL` or `SERVERS`. `SERVERS` carries `sectionKeys[]` (existing SERVER-enforcement
sections, referenced live: editing a section's targets moves the rule) and/or an own
`targetKind` + `servers[]` identical in meaning to the section fields. A rule covers a server when
`ServerTargets.covers` accepts it for any of those targets, using `ServerResolver.facts(serverName)`
exactly like `ServerConnectListener`. A section key that does not exist or is not a SERVER section is
a validation error on write; a section deleted later makes the rule cover nothing for that key (fail
open) and the panel flags it.

When a rule is checked:
- `PROTOCOL` global: at `LoginEvent`, so the login is refused cleanly. Scoped: at
  `ServerPreConnectEvent` for the target server, like a server section.
- `BRAND`, `MOD`, `CHANNEL`: whenever a trait event arrives and at each `ServerPreConnectEvent`, for
  the server the player is connecting to or on. These traits can be unknown at login, so a trait rule
  never refuses a login; a `DENY` here disconnects (or refuses the connect when the player has a
  server) with the rule's message.
- `COMMAND`: at `CommandExecuteEvent`, for the player's current server (global rules need none).
  Without a current server only global rules apply.

Staff holding `blackhole.clientrules.bypass` are exempt from DENY and TEMPLATE (a hit is still
recorded as `bypassed`), so a rule can never lock the team out.

### 3. Actions: `NOTIFY` (default), `DENY`, `TEMPLATE`

- `NOTIFY`: record the hit, tell the team. Nothing changes for the player. It doubles as the dry run:
  the hit counts in the panel show what a rule would hit before an admin switches it to `DENY`.
- `DENY`: record, notify, and refuse. Message = the rule's `denyMessage` (English, plain text, at
  most 200 characters, no formatting codes; default per kind such as "This client or version is not
  allowed on this server."). Refuse means: login refused (global protocol), connect refused
  (scoped protocol, trait rule with a current server), disconnect (trait rule with no current server
  or a mid-session trait), command denied (command rule).
- `TEMPLATE`: record, notify and ask the backend to apply the rule's `templateKey` to the owner. The
  backend applies it through the same internal path as the ELO automatic punishments: no human actor,
  a system source `client-rule:<key>`, a Ban ID, template steps and ELO cost, appealable. The proxy
  learns the result from the hit response and from the usual punishment sync, then shows the normal
  punishment screen. Guards:
  - applied at most once while the owner has an active punishment from the same rule (a repeated
    hit does not escalate the template's steps);
  - the template must exist (foreign key; deleting a referenced template answers 409 as today);
  - backend down means no punishment is applied (fail open), the hit is retried on the next trait
    event and the local `NOTIFY` still happens;
  - never on a bypassed staff member.
- Player-facing text is English only and lives on the rule (and a few fixed defaults), not in the
  de/en bundles. Staff-facing text (in-game notice, panel) is localised de/en as everywhere else.

### 4. Command rules, signed commands and SignedVelocity

`CommandExecuteEvent` fires for proxy commands and for commands Velocity forwards (the default for a
label it does not know), before either happens. The line is normalised: leading spaces and `/`
stripped, first word lower-cased; a `plugin:label` is matched both as written and as `label`, so
`minecraft:op` cannot slip past a rule for `op`. Aliases registered only on a backend server are
different words; admins add them to the rule (the catalogue lists the aliases the proxy's command
tree shows).

Denying: `CommandResult.denied()` stops proxy handling and forwarding. The javadoc of
`CommandExecuteEvent.SignedState` states that cancelling a command in state `SIGNED_WITH_ARGS` makes
Velocity kick the player; `SIGNED_WITHOUT_ARGS`, `UNSIGNED` (clicks, 1.20.5+) and `UNSUPPORTED`
(before 1.19.3) are safe. This is the same problem as signed chat and gets the same answer as
`MuteChatDenial`: a `CommandDenial` helper denies when the state is not `SIGNED_WITH_ARGS` or when
SignedVelocity is loaded on the proxy (it carries the cancellation to the backend, which then does not
see a signed command it never received), and otherwise does not deny. In the second case the player
gets the rule's message as a chat line, the hit is recorded with `enforced = false` and the existing
startup warning about SignedVelocity is extended to name commands. The helper reuses the plugin-id
constant of `MuteChatDenial`. Whether a command-line event exposes the state as
`getInvocationInfo().signedState()` is confirmed against 4.3.0 in task 5.1.

`hideCommand`: `PlayerAvailableCommandsEvent` (Velocity waits for it) lets the proxy remove the rule's
literal nodes from the command tree sent to the client, for rules that cover the player's server. It
is cosmetic (tab completion), applies to commands that appear in the proxy's merged tree, and is never
the enforcement.

### 5. Spoofable signals: how the model reflects it

- Default action `NOTIFY`; the panel preselects it.
- `TEMPLATE` on `BRAND`, `MOD` or `CHANNEL` is rejected unless the request sets
  `acknowledgedSpoofable = true` (stored, shown as a badge); on `PROTOCOL` and `COMMAND` it needs no
  acknowledgement (facts the proxy observed). Editing the kind or action clears the flag.
- The panel and the docs say it in the editor: "The client controls this value and can fake it. A
  hit is a signal, not proof. Appeals are open for every automatic punishment."
- The fingerprint (decision 7) never triggers a rule action or a punishment on its own.
- A negated brand rule does not hit a client that sends no brand: an allowlist is bypassable by
  suppressing the brand. Written down, not fixed.

### 6. Hits, notification, backend intake

- The proxy reports a hit with `POST /client-rule/hit {rule, owner, server?, enforced, bypassed}`
  (`PROXY`), once per owner and rule per proxy session (in-memory dedupe, not rate limiting; a command
  rule dedupes per 60 seconds per owner and rule so a held key does not flood the table). The owner is
  the existing SHA-512 owner hash. No brand, channel, mod or command text is sent or stored: the rule
  key already says what matched.
- Table `client_rule_hit(identifier, rule_key, owner_hash, at, action_taken, enforced, bypassed,
  server_name)`; `server_name` is an infrastructure name, not personal data. Retention
  `blackhole.clientrules.hit-retention-days` (default 30), swept daily like the IP sweeper; the rule
  keeps only an aggregate count (a counter column updated in the same statement) after hits expire.
- Events: `client-rule.hit` on the topic exchange with `{rule, owner, kind, action, at}` (no traits, no
  names), non-blocking like the other publishers.
- In-game notice: the proxy that saw the hit announces it on a Redis pub/sub channel like
  `ReportNoticeChannel`; every proxy tells players with `blackhole.clientrules.notify` (rule display
  name, player name, kind, action, "enforced / not enforced"). Like `ReportNotice` it carries the name,
  travels only over pub/sub and is never stored. If Redis is down the announcing proxy delivers it
  itself.
- Panel: the rule view lists hits (owner hash resolved to the player through the existing lookup, rule,
  time, action). The player page lists the player's recent hits.

### 7. Client fingerprint: scheme, correlation, privacy

Traits (client side of the proxy): normalised brand (lower case, trimmed, control characters removed,
at most 64 characters, empty if unknown), the sorted set of registered channel ids **excluding the
`minecraft:` namespace** (every vanilla client has those), and the sorted legacy mod list as
`id@version`. The protocol version is deliberately not part of the hash (it changes with every client
update and would split one person into many).

Canonical form, versioned and unambiguous (length-prefixed, so values cannot collide):

```
v1
brand:<len>:<brand>
channels:<count>:(<len>:<channel>)*
mods:<count>:(<len>:<id@version>)*
```

`fingerprint = hex(HMAC-SHA256(fingerprintKey, canonical))`, 64 hex characters, stored with
`scheme = 'v1'`. `fingerprintKey` is derived by HKDF from `BLACKHOLE_FINGERPRINT_KEY`
(`blackhole.clientrules.fingerprint-key`), a key of its own, not the IP salt and not the Ban ID key,
so a leak of one does not unlock another and each can be rotated on its own schedule. A
`ClientFingerprintKeyGuard` follows `BanIdKeyGuard`: unset outside `dev`/`test` means fingerprinting is
off (like the IP salt: `detectionEnabled` false, the submit route answers 503), unset in `dev`/`test`
means a random per-run key. Treat the key as effectively unrotatable in place: rotating makes old and
new fingerprints uncorrelatable; the `scheme` column lets a future `v2` (new trait set or algorithm)
coexist, and a rotation is a new scheme/key pair with a documented overlap, not an in-place change.

The proxy sends the traits, not the hash: `POST /evasion/fingerprint {owner, brand, channels[], mods[]}`
(`PROXY`) over the authenticated TLS channel, exactly as the raw IP travels today; the backend hashes
and never persists the raw values (a request body is not logged). The proxy sends it once the traits
settle (`clientRules.settle`, default `PT5S` after the first successful server connect, measured with
the injected `Clock`/scheduler) and again only if the trait set changed. Storage:
`client_fingerprint(identifier, fingerprint, scheme, owner_hash, first_seen, last_seen,
occurrence_count)`, unique on `(fingerprint, owner_hash)`. No raw brand, channel or mod is stored,
none ever sits next to a raw UUID (the owner is the existing hash).

Correlation, in `ClientFingerprintService` next to `IpCorrelationService`:
- Two or more distinct owners with the same fingerprint inside `blackhole.evasion.fingerprint.window-days`
  (default 14) are linked.
- **Common fingerprint cut-off**: a fingerprint shared by more than
  `blackhole.evasion.fingerprint.max-owners` (default 5) owners in the window is "common" and links
  nobody (all vanilla clients, a popular modpack). A fingerprint whose channel and mod sets are both
  empty and whose brand is one of `vanilla`, `fabric`, `forge`, `neoforge` or empty is "generic" and is
  never stored for correlation at all (it carries no information), though rules still see the traits.
- The sightings response gains `via` per linked owner: `IP`, `CLIENT` or `BOTH`. `CLIENT` alone is
  shown as a weak hint ("same client setup"); `BOTH` is the strong one. `evasion.detected` is still
  published only for the IP signal; a new `evasion.client_match` event (owners hashed, no fingerprint
  value) is published for `CLIENT`/`BOTH` so the Discord and panel notifications can use it later.
  Nothing in the ELO engine reads it.
- Retention: `blackhole.evasion.fingerprint.retention-days` (default 30; weaker signal, shorter than
  the 90 days of the IP tokens), swept daily by `ClientFingerprintRetentionSweeper`. Rows of an owner
  are deleted with the profile erasure/anonymisation path; rule hits of the owner too.
- Why keyed hashes are enough: staff never need the raw traits, only "same as that banned account".

### 8. Catalogue: aggregated, k-anonymous, never what a player typed

The proxy reports `PUT /client-rule/catalogue` (`PROXY`, full snapshot, pushed on start, on change
debounced, and as a heartbeat; shared-cache key per proxy with TTL `PT24H`, mirroring
`CacheKeys.cloudnetCatalogue`). `GET /client-rule/catalogue` is `VIEWER` and up and refused for
`PROXY`. Contents:
- brands and channel ids with the number of distinct players currently or recently seen, **listed only
  when at least 3 distinct players showed them** (`clientRules.catalogue.minPlayers`), counted in proxy
  memory with the owner hashes thrown away after counting; a rare channel that identifies one person
  is therefore never shown;
- command labels taken from the literal children of the command tree in
  `PlayerAvailableCommandsEvent` (what exists, not what players typed, so no typed secret can reach
  it), including namespaced forms;
- the protocol versions the proxy supports (`ProtocolVersion.SUPPORTED_VERSIONS`) with names.
A cache outage means `PUT` 503 and `GET` `available: false`; nothing in enforcement depends on the
catalogue, rule patterns are free text.

Why raw brand/channel names may be shown to staff: they are configuration inputs an admin needs to
write a rule, appear only aggregated with a count and never linked to an owner. A rule's own patterns
are admin-authored. Hits and fingerprints show no raw trait.

### 9. Backend layering and routes

Package `clientrule/`: `ClientRuleApi` (OpenAPI annotations) implemented by a thin
`ClientRuleController` (`@Version(ApiVersion.V1)`, one service call per method), `ClientRuleService`
(validation, persistence, section/template existence checks, answers sealed DTO variants),
`ClientRuleHitService` (intake, dedupe by statement, template application via the automatic-punishment
service), `ClientRuleCatalogueService`, `ClientRuleChangeNotifier` (reload signal for proxies, same
shape as `SectionChangeNotifier`), `ClientRuleHitRetentionSweeper`. Persistence uses plain statements /
repository calls and no `@Transactional` (multi-row writes are one statement or ordered with
compensation, as `CaseChanges` does); the rule write inserts the rule and its pattern, server and
section child rows in one CTE statement.

Routes and roles (the matrix in `staff-authentication` gets the rows; `PermissionMatrixIntegrationTest`
and `RouteCoverageIntegrationTest` hold them):

| Route | Role |
|-------|------|
| `GET /client-rule`, `GET /client-rule/{key}` | `VIEWER` and up, `PROXY` |
| `POST /client-rule`, `PUT /client-rule/{key}`, `DELETE /client-rule/{key}` | `ADMIN` |
| `GET /client-rule/{key}/hits`, `GET /client-rule/hits?owner=` | `VIEWER` and up, `PROXY` denied |
| `GET /client-rule/catalogue` | `VIEWER` and up, `PROXY` denied |
| `PUT /client-rule/catalogue` | `PROXY` only |
| `POST /client-rule/hit` | `PROXY` only |
| `POST /evasion/fingerprint` | `PROXY` only |
| `GET /evasion/{owner}/sightings` (existing) | `VIEWER`; response gains `via` |

"Rules can be edited by ADMIN; the catalogue and the rules are readable by the team roles" is the
user's decision of 2026-10-24. Every write records the token's `oid` as `recorded_by_oid` (verified
actor); no client-supplied actor field. No app-level rate limiting on any of these routes.

### 10. Velocity structure

Package `clientrule/`: `ClientTraits` (immutable snapshot) and `TraitCollector` (a concurrent map
owner → traits, filled by `PlayerClientBrandEvent`, `PlayerChannelRegisterEvent`, `PlayerModInfoEvent`
and the protocol at login, dropped at `DisconnectEvent`; nothing persisted on the proxy), `RuleEngine`
(pure), `RuleRegistry` (in-memory rules, reload on notification), `ClientRuleEnforcer` (turns a hit
into deny/disconnect/command result, reports it through `BackendGateway`), `CatalogueReporter`,
`FingerprintSubmitter`, `ClientRuleNotifier` (the staff notice), `CommandDenial`. Listeners:
`ClientRuleListener` (trait events, login, pre-connect), `CommandRuleListener`
(`CommandExecuteEvent`, `PlayerAvailableCommandsEvent`). `PlayerClientBrandListener` keeps recording
the brand as session info. Only these classes import the new events; the engine and the denial helper
take plain values so they are testable without a proxy. Config (`config.json`, env-overridable like
CloudNet): `clientRules.enabled` (`BLACKHOLE_CLIENT_RULES`, default `true`), `settle` (`PT5S`),
`catalogue.minPlayers` (3), `catalogue.heartbeat` (`PT15M`). Event handlers that call the backend run
async, never on the event thread blocking the connection; a rule decision that needs only memory is
made synchronously.

### 11. Failure behaviour (all fail open)

Backend or Redis down: the in-memory rules keep enforcing `NOTIFY`/`DENY`; hits are not recorded
(logged at debug), `TEMPLATE` applies nothing. No rules loaded (start with the backend down, 404 from
an older backend): nothing enforced, one warning. Unknown server, no CloudNet: a scoped rule does not
cover the server (fail open, one warning per name, as sections do). Traits not known yet: trait rules
wait for the event. Fingerprint key unset: no fingerprinting, rules unaffected.

### 12. Tests (F.I.R.S.T.)

Pure units with injected `Clock` and a manual scheduler, no sleeps: rule validation table, glob and
normalisation tables (namespaced commands, leading spaces, case), `RuleEngine` (kind x scope x action
x bypass), `CommandDenial` (every `SignedState` x SignedVelocity present/absent), canonical string and
HMAC known-answer vectors (test keys from `TestKeys`, never real key material), generic and common
fingerprint cut-offs, k-anonymity of the catalogue, `FakeCloudNetView` for scopes. Backend integration
tests on Testcontainers: migrations (constraints, cascade, FK to templates), routes with the matrix
(every role), TEMPLATE application once per active punishment, retention sweepers, erasure removes
hits and fingerprints, a `ClientRuleNoLeakTest` that scans logs, events and metrics for raw brand,
channel, mod and fingerprint values (like `BanIdNoLeakTest`). Velocity tests use `RecordingProxy`
fakes: brand/channel events arriving after login and after connect, deny/disconnect paths, a signed
command with and without SignedVelocity. Panel: Vitest per view, role gating, i18n key parity.
A bug found later is reproduced by a failing test first.

## Flyway migrations

Append-only. No version number is fixed in this change: each migration takes the next free Flyway
version at implementation time (other changes are landing migrations concurrently).

- `V<next free>__client_rule.sql`: `client_rule` (identifier, rule_key unique, display_name, kind with CHECK,
  protocol_min, protocol_max, negate, scope_kind, target_kind, hide_command, action with CHECK,
  deny_message, template_key FK to the template table `ON DELETE RESTRICT`, acknowledged_spoofable,
  enabled, hit_count, created_at, updated_at, recorded_by_oid), `client_rule_pattern`,
  `client_rule_server`, `client_rule_section` (child tables with position, `ON DELETE CASCADE`).
  Check constraints: patterns only for pattern kinds, range only for `PROTOCOL`.
- `V<next free>__client_rule_hit.sql`: `client_rule_hit` with indexes on `(rule_key, at)` and `(owner_hash)`.
- `V<next free>__client_fingerprint.sql`: `client_fingerprint` with unique `(fingerprint, owner_hash)` and indexes on
  `fingerprint` and `last_seen`.
Existing rows: none of these tables exist yet, so nothing is migrated; no existing table changes.
The session-info columns for brand and protocol stay as they are.

## Risks / Trade-offs

- Spoofing: any client-side rule on brand or channels is bypassable (suppress or fake). Mitigated by
  language, `NOTIFY` default, the acknowledgement, appeals, and by never punishing on fingerprints.
- False positives: shared modpacks and launchers share fingerprints; the cut-off and "weak hint"
  wording limit harm. A rule on a channel a legitimate mod registers can hit innocents: the hit counts
  under `NOTIFY` are the dry run.
- Signed commands without SignedVelocity cannot be denied (documented, warned at start).
- Brand and channel traits arrive late; a cheat client has a window between joining and the first
  event. Accepted; `DENY` disconnects when the trait arrives.
- New personal data (fingerprint hashes) increases the erasure surface; covered by the erasure path,
  retention and tests.
- A hit in a busy lobby floods staff chat: dedupe per owner and rule per session; infra rate limits
  the API; staff opt in to the notice by permission.

## Migration Plan

Roll out backend PRs 1 to 3 first (additive routes, no proxy change needed), then upgrade proxies (PR
4, 5), then the panel (PR 6). Create rules as `NOTIFY`, read the hit counts for a week, then switch
chosen rules to `DENY` or `TEMPLATE`. Set `BLACKHOLE_FINGERPRINT_KEY` (documented in the README and
the deploy notes) before enabling fingerprints. Rollback: disable rules in the panel, or remove the
key; routes and tables are inert without callers.

## Decisions of 2026-10-24 (open questions resolved)

1. **Trait `DENY` timing.** A `DENY` on a brand, mod or channel rule refuses as soon as the trait is
   known: if it is known at the first `ServerPreConnectEvent` the connect is refused there, otherwise
   the player is kicked when the trait arrives. There is no settle delay for enforcement (the settle
   period only applies to fingerprint submission).
2. **No concrete value on a hit.** A hit stores and shows only rule key, owner hash, time, action
   and server, never a raw trait or the concrete value a glob matched.
3. **Defaults kept.** Hits 30 days, fingerprints 30 days, correlation window 14 days, all
   configurable (`blackhole.clientrules.hit-retention-days`,
   `blackhole.evasion.fingerprint.retention-days`, `blackhole.evasion.fingerprint.window-days`).
4. **`TEMPLATE` opens no case note.** The punishment's own timeline entry is enough.

## Open Questions

None open.
