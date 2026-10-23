# Design

## D1. Report evidence reuses the chat-capture path

`ReportEvidence` mirrors `ChatEvidence`: the same `ChatSnapshotBuilder` (redaction, per-snapshot
aliases, line caps), the same `chat-capture` switch, source system and timeout. The evidence is
posted as pending evidence of the **reported** owner under a fresh correlation id; the report
references the returned evidence id in `evidenceReferences`. No `onBehalfOf` is sent, so the evidence
records the proxy's token only and the reporter's UUID is stored nowhere.

Known limit: pending evidence that no punishment claims is removed after `blackhole.evidence.pending-ttl`
(one day by default). Part of PR 3 keeps evidence that an open report references from that sweep, and a
resolution that applies a template claims it for the new punishment.

## D2. Staff notices travel over Redis, published by the proxy that took the report

The backend event `report.created` carries hashes only, on purpose (the broker is a second at-rest
copy), so it cannot name the reporter or the reported player. The proxy that accepted the report knows
both names and the text, so it publishes a short-lived notice on the Redis channel
`blackhole:report:created` (pub/sub, nothing is stored). **Every** proxy, the publisher included,
subscribes and delivers the notice to its own online staff, so each staff member gets it exactly once
whichever proxy they are on. If Redis is unavailable the publisher delivers locally instead.

## D3. Pending feedback is a small table, delivery is "fetch, show, acknowledge"

The migration for PR 3 is the next free `V<n>__report_feedback.sql` at that time (V13 and later may be
taken by open pull requests, so it is chosen right before the bump together with the client spec
version). A row holds the reporter's owner hash, the report id, the outcome (`ACTIONED` or
`DISMISSED`), `createdAt` and `expiresAt`; it never holds the punishment. The same proxy routine serves
login and the live poke from the event: fetch the pending rows of the owner, show them, acknowledge
each shown row (deleted). Acknowledging is idempotent, so two proxies racing cannot show a row twice
to the same session in a harmful way, and an expired row is neither returned nor kept.
