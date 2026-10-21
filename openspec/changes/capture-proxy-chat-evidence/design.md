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
   transcript is intelligible. Speakers are owner hashes; no names. Client-side text only, as
   seen by the proxy (already after chat-filter plugins that ran earlier).
3. **Pseudonymisation / redaction.** The backend rejects UUIDs/IPs in structured fields and
   redacts IPs in chat text; the proxy still sends hashes only and also passes text through the
   same IP regex before sending to keep raw IPs off the wire where the proxy is on a separate
   network segment.
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

- **Transient processing of all public chat**: mitigated by opt-in, bounded size/age, memory
  only, no logging; operator documents lawful basis (see `add-punishment-evidence` design).
- **Memory use**: bounded by online players x lines x text cap (256 chars).
- **Rejected evidence** (e.g. backend validation): punishment unaffected; staff warned.
- **Count requires a backend call on the denial path**: async with fallback, so a slow backend
  never delays login denial.

## Migration Plan

Plugin-only, off by default; upgrade the backend first. Rollback: disable the setting.

## Open Questions

Whether context lines from other speakers are acceptable under the operator's privacy review
(`context-lines: 0` disables them).
