## Why

A muted player on a network without SignedVelocity can still talk. Their chat is shown to everyone:
the proxy tells the player they are muted, but since Minecraft 1.19.1 the proxy cannot cancel a
signed chat message without disconnecting the client, so the proxy lets it through. Staff see a
mute that does not hold, and the player learns that the mute is only a notice. The same gap applies
to private and emote commands such as `/msg`, which the proxy does not check for mutes at all.

The current spec already says a muted player's message MUST be denied. The code does not meet that
when SignedVelocity is missing, and the operator is only told in a startup warning.

## What Changes

- **Muted chat is blocked on every proxy, with or without SignedVelocity.**
  - With SignedVelocity loaded: unchanged. The proxy denies the message and SignedVelocity carries the
    cancel to the backend server.
  - Without SignedVelocity: unsigned chat (1.19.0 and older) is denied as today. Signed chat from a
    muted player is not delivered either: the proxy disconnects that player at the chat attempt and
    shows the mute screen as the disconnect reason. A reconnect is allowed. This is the only way a
    proxy can stop a signed message on its own (see design, decision 1).
- **Muted commands.** A configurable list of commands (default `msg`, `tell`, `w`, `whisper`, `me`,
  `r`, `reply`) is denied for muted players, matched like the command rules (namespace-aware). Signed
  commands follow the same SignedVelocity rule as chat.
- **SignedVelocity becomes a deployment requirement for OLF, not an optional extra.** The proxy checks
  it at startup and reports its state (`signedVelocity` present or absent) to the backend, which shows a
  banner on the panel and exposes a metric while it is missing. The startup warning stays, and is
  promoted to an error log line.
- **Runbook:** every backend server that players can reach needs `SignedVelocity-Paper` (or the Sponge,
  Fabric or Minestom build) in addition to the proxy plugin. The list of OLF servers is written into the
  runbook by the deployment task.
- **BREAKING (behaviour, not API):** signed chat from a muted player is now disconnected, not delivered,
  when SignedVelocity is missing. Operators who relied on the delivery must install SignedVelocity.
  The generated client and the Velocity plugin's Java API are unchanged.

## Capabilities

### Modified Capabilities

- `proxy-enforcement`: the chat enforcement requirement gains the fallback for signed chat without
  SignedVelocity; a new requirement covers muted commands; a new requirement covers the relay state
  check and its reporting.

### New Capabilities

None.

## Impact

- Velocity plugin: `PlayerChatListener`, `MuteChatDenial`, a new mute command gate next to
  `CommandRuleListener`, `BlackholeVelocity` (startup check and relay state), config (`config.json`) for
  the command list. No change to `CommandDenial` (client rules keep their own policy, see design).
- Backend: one read/write route for the proxy's relay state under the PROXY role, a health/metric flag
  and a panel banner. No change to punishment rows.
- Client: the OpenAPI client spec gains the relay state route; regenerated at implementation time.
- Web panel: a banner on the admin layout when any proxy reports the relay as missing.
- Docs: `README.md` "Muting and signed chat" and the runbook; `.claude/rules/client-rules.md` is not
  changed.
- Dependencies: none on other open changes. It builds on the chat denial of
  `add-client-fingerprint-rules`.

## Out of scope

- Verifying that every backend server has SignedVelocity installed. The proxy cannot see backend
  plugins; the runbook and the operator's check cover it.
- Changing the client-rule command policy (`CommandDenial`): a signed command that a client rule denies
  is still delivered without SignedVelocity. Aligning the two policies is a follow-up decision.
- A companion plugin for the backend servers (rejected, see design).
- Muting in other channels (party chat, proxy-only plugins).

Ships as: fix(velocity): enforce mutes without signed velocity
