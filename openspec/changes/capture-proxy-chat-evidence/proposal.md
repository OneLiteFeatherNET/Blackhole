# Proposal

**Ships as:** `feat(velocity): attach chat evidence to staff punishments` (additive, optional
feature, disabled by default).

## Why

Chat-based punishments are the most common and the hardest to review later, because the proxy
sees the chat and nobody else keeps it. Staff should not have to screenshot. When a staff member
punishes a player, the proxy can attach the target's recent chat as `CHAT_LOG` evidence, and
the player's punishment screen can say how many pieces of evidence exist - without revealing
any of them.

## What Changes

- **Optional** (`evidence.chat-capture.enabled`, default `false`) in-memory chat ring buffer in
  the Velocity plugin: the last `N` (default 20, max 50) public chat lines per player, each
  line kept for at most `max-age` (default PT10M), lost on restart, **never persisted or
  logged**, cleared on disconnect. Commands are not buffered.
- On a staff `/ban`, `/mute` or `/punish`, the proxy snapshots the buffer of the target (and the
  surrounding lines of other speakers within the window, speakers as owner hashes) and sends
  it with the punish call (`evidenceCorrelationId`) as `CHAT_LOG` evidence, `sourceSystem`
  `velocity`. Failure to attach never blocks or undoes the punishment; it is logged without
  content.
- Staff command feedback mentions "evidence attached (n lines)".
- The login/chat denial screen shows "Beweise: 3" / "Evidence: 3" using
  `GET /punishment/{ref}/evidence/count`, fetched off the event loop with a short timeout; if the
  call fails the line is omitted.
- Localised messages (de/en) and config documented in the plugin config with comments.

Out of scope: anticheat/replay integrations (third parties call the backend API directly),
persisting chat on the proxy, private messages and commands, showing any evidence content to
players, proxy-side file upload, and the admin UI.

## Capabilities

### New Capabilities

- `proxy-evidence-capture`: the optional chat buffer, snapshot-on-punish and the evidence count
  on the punishment screen.

### Modified Capabilities

None.

## Impact

- `velocity/` only: chat listener, buffer, punish command flow, denial screen, config, messages,
  tests; consumes the regenerated Java client from `add-punishment-evidence`.
- Depends on `add-punishment-evidence`. Privacy review: this adds transient processing of all
  public chat in proxy memory; operators must be able to keep it off.
