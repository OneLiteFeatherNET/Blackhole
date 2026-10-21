# Design

## Context

The Velocity plugin already hashes player identity (SHA-512 of the UUID), listens to chat for
mute enforcement and toxicity scoring, executes staff commands (`/ban`, `/mute`, `/punish`) via
the generated Java client, and shows localised denial screens built from punishment data and the
Redis mirror. The backend side (`add-punishment-evidence`) provides `POST /evidence`, the
`evidenceCorrelationId` on punishment creation and the count endpoint.

## Goals / Non-Goals

**Goals:** capture chat context at punish time with minimal data held; show the evidence count
to the punished player.

**Non-Goals:** durable chat logging, private-message capture, content to players, upload.

## Decisions

1. **In-memory ring buffer, opt-in.** `ChatBuffer` keyed by owner hash (not UUID) with a bounded
   deque per player (`lines`, `max-age`), evicting on insert and on a periodic cleanup via the
   proxy scheduler; removed on disconnect. A global cap (`max-tracked-players` = online count)
   is implicit. Disabled by default because buffering everyone's public chat is a processing
   operation the operator must consciously enable (documented in the plugin config comments).
2. **What is captured.** The target's lines plus up to `context-lines` (default 5) preceding
   lines from other players on the same backend server within the time window, so the
   transcript is intelligible. Owner decision: context lines from other players are approved. Other speakers are replaced by a
   stable per-evidence alias ("Spieler A", "Spieler B", ... assigned in order of first
   appearance within that one snapshot; the target is aliased too, with the real owner hash only on
   the evidence's `owner`), never names or UUIDs, so the alias cannot be correlated across
   evidence items. Client-side text only, as
   seen by the proxy (already after chat-filter plugins that ran earlier).
3. **Pseudonymisation / redaction.** The backend rejects UUIDs/IPs in structured fields and
   redacts IPs in chat text; the proxy also redacts IPv4/IPv6 addresses and URLs (`[ip]`, `[url]`) from the chat text
   before sending, so neither travels over the wire. (The backend's CHAT_LOG schema accepts the
   alias in `speaker`; see the change `add-punishment-evidence` task 2.1.)
4. **Order of operations.** The punish call is the source of truth. The proxy generates a
   `correlationId` (random UUID), posts the `CHAT_LOG` evidence first with `owner` +
   `correlationId`, then performs the punish call with `evidenceCorrelationId`. If evidence
   posting fails or times out (2 s, off the event loop), the punishment proceeds without it and
   staff see a warning; if the punish call fails, the pending evidence expires by TTL.
   *Alternative:* punish first, then attach by returned id - rejected as default because a
   failure between the two calls loses the evidence of an already enforced ban; the
   correlation path loses nothing.
5. **Evidence count on the denial screen.** On login denial or a muted chat attempt the plugin
   asks `GET /punishment/{ref}/evidence/count` asynchronously, caches the count briefly in
   the existing proxy-side cache (30 s) and appends the localised line. The Redis mirror is
   not extended: a count in the mirror would be stale once evidence is attached after the
   ban. Failure or timeout omits the line.
6. **Threading.** All HTTP calls go through the plugin's existing async executor, never the
   Velocity event thread; listener code only appends to the buffer (O(1)).
7. **Config** (`evidence.chat-capture.*`): `enabled`, `lines`, `context-lines`, `max-age`,
   `source-system` (default `velocity`), `timeout`. Documented inline.
8. **No Flyway migrations**: none; no backend change.

## Risks / Trade-offs

- **Transient processing of all public chat, including third parties' lines as context**:
  mitigated by opt-in (off by default), bounded size and time window, RAM only, no logging,
  pseudonymous aliases, URL/IP redaction. GDPR implications are documented in the README:
  third-party data, legitimate interest (Art. 6(1)(f)) with a balancing test as an operator
  note, privacy-notice wording, and `context-lines: 0` as the off switch for context.
- **Memory use**: bounded by online players x lines x text cap (256 chars).
- **Rejected evidence** (e.g. backend validation): punishment unaffected; staff warned.
- **Count requires a backend call on the denial path**: async with fallback, so a slow backend
  never delays login denial.

## Migration Plan

Plugin-only, off by default; upgrade the backend first. Rollback: disable the setting.

## Open Questions

None; owner approved context lines (see decision 2).
