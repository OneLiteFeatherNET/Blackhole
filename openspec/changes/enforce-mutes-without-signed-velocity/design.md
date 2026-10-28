## Context

Checked on `origin/main` (commit `4107a72`, 2026-10-28):

- `velocity/.../listener/PlayerChatListener.java` denies a muted player's chat through
  `MuteChatDenial.deny`. The class javadoc says it outright: signed messages are delivered when
  SignedVelocity is absent, "because cancelling it would disconnect the player".
- `velocity/.../enforcement/MuteChatDenial.java` holds `RELAY_PLUGIN_ID = "signedvelocity"` and
  `deny(event, version)`: signed (1.19.1+) and no relay -> returns `false` without touching the event.
- SignedVelocity is an optional dependency: `BlackholeVelocity.java` line 130
  (`@Plugin(dependencies = @Dependency(id = "signedvelocity", optional = true))`) makes Velocity load it
  first when present. Presence is then read once per start with
  `server.getPluginManager().isLoaded(RELAY_PLUGIN_ID)` (lines 206 and 326). The result feeds
  `MuteChatDenial` and `CommandDenial`. Without the relay, one `logger.warn` (lines 207-211) is written.
- Commands are not checked for mutes. `CommandRuleListener` / `CommandRuleEnforcer` handle client
  rules only, and the README states "Mutes and bans do not block commands".
- Chat policy in the main spec (`openspec/specs/proxy-enforcement`, "Chat enforcement"): a muted
  player's message MUST be denied. The code breaks that rule when the relay is absent.
- No file in this repo mentions the backend servers that run SignedVelocity. The README
  ("Muting and signed chat") says it must be installed on the proxy and on every backend server.

External facts (fetched 2026-10-28):

- Velocity `PlayerChatEvent` javadoc, deprecated `setResult`: "for 1.19.1 and newer, set this as denied
  will kick users." <https://jd.papermc.io/velocity/com/velocitypowered/api/event/player/PlayerChatEvent.html>
  Velocity also rejects modifying a signed message, so rewriting the text is not an option either.
- SignedVelocity project README: a proxy plugin plus a backend plugin per server type (Paper, Fabric,
  Sponge 8/10, Minestom). The plugin "transmit[s] the modification and cancellation status" of Velocity
  messages and commands to the backend over plugin messages. Requirements: Java 21+, Velocity 3.4.0+, Paper
  or Fabric 26.1.2+. It "must" be installed on Velocity and all servers. It has no developer API beyond
  a Minestom initializer. Source: <https://github.com/4drian3d/SignedVelocity>. The repository README
  (`README.md`, "Muting and signed chat") names SignedVelocity 1.5.0 as the tested version; the fetched
  page did not show the releases list, so the version must be checked before the runbook is written.
- The project warns that SignedVelocity-Proxy before 1.3.0 has an exploit. The runbook must pin a
  current version.

## Goals / Non-Goals

**Goals**
- A muted player's chat and private/emote commands are blocked on every OLF proxy, whether or not
  SignedVelocity is installed (user decision: "mutes must really block chat when SignedVelocity is missing").
- A missing SignedVelocity is visible to staff, not only in a log.
- The operator knows which backend servers need the backend plugin.

**Non-Goals**
- Replacing SignedVelocity. Non-Goals also include any change to bans, to the punishment data model, and to
  client-rule command policy.
- Making a muted player's signed message "silently" disappear without SignedVelocity. Velocity cannot do
  that, and no proxy-side workaround exists (see decision 1).

## Decisions

### 1. Without SignedVelocity, a signed chat attempt by a muted player disconnects that player

Decision: the proxy keeps SignedVelocity as the way to block signed chat silently, and makes it a
deployment requirement for OLF (decision 2). When it is missing, a muted player's signed message is not
delivered; the player is disconnected at that attempt with the mute screen as the reason. Unsigned chat
(client older than 1.19.1) keeps the current denial and notice.

Why: the proxy has no other way to stop a signed message. Velocity's own javadoc states that denying a
1.19.1+ chat event kicks the player, so "deny and tell" is impossible, and rewriting the text is refused
by the same signature check. The user's Must ("mutes must really block chat when SignedVelocity is
missing") can only be met by a disconnect on the proxy or by a backend-side cancel (rejected below). The
disconnect is harsh, but it is the only option that the proxy can run alone and that ends the message.

Rejected alternatives:
- **Keep delivering signed chat and only notify the player** (today). Violates the existing "Chat
  enforcement" MUST and the Must. Rejected.
- **Option a alone: make SignedVelocity a hard requirement and do nothing else in code.** The Must
  says mutes must block chat when it is missing, so a gate alone leaves a known hole that only staff
  checks can close. Used here as the operator gate (decision 2), not as the whole answer.
- **Option b alone, with no gate and no reporting.** Works, but staff would not know why a muted player
  keeps being kicked, and nothing would say that the relay is missing. Rejected as the only mechanism;
  combined with decision 2 it becomes the recommendation.
- **Option c: a Blackhole companion on every backend server that cancels chat.** Rejected. It would be a
  second, separately maintained cancel-sync plugin for Paper, Sponge, Fabric and Minestom, reimplementing
  what SignedVelocity already does over plugin messages (its README). The proxy would still have to stop
  forwarding the message to other servers, so it still needs a proxy-side decision, and the backend plugin
  would be the only thing preventing the message from showing. Two plugins to keep in step is the failure
  mode this project is trying to avoid.
- **Rewrite the message to an empty string.** Velocity rejects modified signed messages with the same kick.
  Rejected.

Consequence: a muted player on a signed client who is still muted will reconnect and be disconnected
again on each attempt, which is intended. The mute screen names the reason, and the disconnect is logged
without the message text.

### 2. SignedVelocity is required for OLF: startup error, relay state report, banner and metric

Decision: the proxy checks `isLoaded("signedvelocity")` at startup and logs at ERROR (not WARN) when it is
missing, naming the consequence (signed mutes are enforced by disconnect; the proxy cannot stop signed
messages). It reports its relay state to the backend under the PROXY role with a new route
`PUT /proxy/capabilities` (body `{proxyId, signedVelocity: {present, version?}}`, answered 204). The backend
stores the last report per proxy in Dragonfly with a TTL of twice the proxy's heartbeat interval (a
shared store, so any replica answers, as in `shared-cache`). The admin panel shows a banner while any
proxy reports `present=false` or has not reported; a Micrometer gauge `blackhole.proxy.signedvelocity`
(1 present, 0 absent, -1 unknown) is set per proxy. Staff are not refused for applying a mute while the
relay is absent. The mute is still recorded, because refusing it would lose the case.

Why not a hard stop: refusing to start mute enforcement would also stop the unsigned-chat denial and the
bans on that proxy. The gate is about visibility; decision 1 is the enforcement.

Rejected alternatives:
- **Refuse to start the whole plugin without SignedVelocity.** Stops bans and kicks on the network for a
  mute-only problem. Rejected.
- **Refuse new mutes while the relay is absent.** Loses the staff action and the case record; the player is
  still disconnected on signed chat by decision 1. Rejected; the banner says it instead.
- **Log only.** Today's state. Staff do not read proxy logs on a schedule. Rejected.

Open question: the proxy cannot see the backend plugins. A backend that lacks SignedVelocity is not
detected by the proxy (see decision 4).

### 3. Muted commands: a configurable list, same policy as chat

Decision: `mute.commands` in the Velocity `config.json` (default `msg`, `tell`, `w`, `whisper`, `me`, `r`,
`reply`). A `CommandExecuteEvent` gate for muted senders denies a listed command (matched with the same
normaliser as `CommandWords`: leading spaces and slash removed, lower-cased, `namespace:label` matched as
written and as `label`). For a command with `SignedState.SIGNED_WITH_ARGS` the rule of decision 1 applies:
denied with SignedVelocity, otherwise disconnect with the mute screen. Other signed states are denied directly.

Why the same policy as chat: `CommandExecuteEvent.SignedState` has the same problem as chat (Velocity kicks on
cancelling a `SIGNED_WITH_ARGS` command, which the proxy's own `CommandDenial` already encodes). Muting
private messages is the common complaint, so the list covers them out of the box.

Rejected alternatives:
- **Mute by a fixed list only in code.** Operators differ (a network may run `/tpa`, `/mail`). Rejected.
- **Deny all commands for muted players.** Would stop `/spawn` and `/hub`; a mute is about talk. Rejected.
- **Use the client-rule `CommandDenial` policy (deliver signed commands without SignedVelocity).** That is
  the leak the user asked to close for chat, and it would give mutes a second, weaker path. Rejected for
  mutes; `CommandDenial` for client rules stays as it is (see open question 2).

### 4. Backend server side: the runbook lists every reachable backend and the check is manual

Decision: the runbook (`docs/` or the deployment chart's README, whichever the deployment task names)
lists every backend server a player can reach through the proxy, with its SignedVelocity backend build
(Paper/Fabric 26.1.2+, Sponge 8 or 10 build, Minestom library 1.3.0+ or extension). Adding a server is a
checklist step. The proxy cannot verify backend plugins through the Velocity API, so no automated check is
added in this change. A later change may probe a backend over the plugin channel that SignedVelocity uses
if the maintainer wants automation; this change does not depend on it.

Rejected alternatives:
- **Automated backend probe.** Depends on an undocumented channel of SignedVelocity; not verifiable without
  running servers. Deferred.

### 5. Relay detection is read at startup and reported as state

Decision: `MuteChatDenial` and `CommandDenial` keep taking the boolean at construction, as they do today.
The state reported to the backend (decision 2) is the same boolean, read once at startup. A relay that is
installed or removed while the proxy runs needs a restart, as before.

Rejected alternatives:
- **Poll the plugin manager at runtime.** The relay's presence does not change in practice; a restart is
  already needed for plugin changes. Rejected.

## Risks / Trade-offs

- A muted player on a signed client is disconnected at every chat attempt when the relay is absent. This
  is intended, but it will feel harsh. The banner makes the cause visible to staff so they install the relay.
- A backend server without SignedVelocity still shows a signed message when the relay is present. The
  proxy cannot detect that (decision 4). The runbook and the checklist are the control.
- The command list is operator-set. A missing entry leaves that command open to a muted player.
- The proxy's relay report is a new write from the proxy to the backend. It uses an existing machine identity
  and adds one route; the generated client gains one method (regenerated at implementation time).

## Migration plan

- No Flyway migration: the relay state is held in Dragonfly, and mutes are not changed.
- Deploy order: backend first (new route, banner, metric), then proxies (new code reports the state), then
  the runbook checklist is applied to every backend server. Until a proxy reports, the banner shows
  "unknown", which is accurate.
- Existing configs: `mute.commands` defaults apply when the key is absent. No config key is required.
- Rollback: the proxy can be rolled back to the previous plugin; mutes then return to delivering signed chat
  without SignedVelocity, the old behaviour. The backend route can stay.

## Open questions

1. Is a disconnect at each signed chat attempt acceptable for OLF, or does the maintainer want option c
   (a Blackhole backend companion) after all, accepting the second plugin? This proposal recommends the
   disconnect.
2. Client-rule command denials (`CommandDenial`) still deliver signed commands without SignedVelocity, while
   mute commands are disconnected. Should both use one policy? Not decided here.
3. Which SignedVelocity version is pinned? The repository README says 1.5.0; the upstream page did not show
   the release list when checked. Verify before the runbook is written, and check the proxy-side security
   fix (before 1.3.0).
4. Which OLF backend servers run which SignedVelocity build? The inventory is not in this repository. The
   deployment task must produce it.
5. Heartbeat interval for the relay report: the TTL in decision 2 assumes a heartbeat; confirm the interval
   in the proxy's existing scheduler.
