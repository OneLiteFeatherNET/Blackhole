---
paths:
  - "backend/src/main/java/**/warning/**/*.java"
  - "velocity/src/main/java/**/WarningHandler.java"
---

# Warning

A warning is a staff punishment step that never stays active. Source: `openspec/changes/add-warnings/design.md`
(decisions 1, 3, 4, 6 and 8).

## A warning is never active (decision 1)

- A warning is a `punishments` row with `kind = 'WARN'`, `lifted = false`, not permanent, no expiry, `section` null.
  It has a Ban ID and sits in the player's history and case.
- It is never written to `PunishmentProfileEntity.active` (the warn path does not call `placeAndRecord`) and never reaches the
  proxy mirror: no `punishment.created` or `punishment.updated` is published for it.
- Every active-state read filters `kind = 'PUNISHMENT'`. A new active-state read must do the same.
- Escalation counts warnings only inside the template's warn window (`warnWindow`, else the escalation window); see
  `PunishmentApplicationService.plan`. Warnings stay in the history either way.

## Delivery and acknowledgement (decisions 4 and 6)

- `punishment.warned` payload: `{eventId, warningId, owner, occurredAt}`. No reason text, name, raw UUID or IP. The proxy reads the
  text with `GET /warn/{id}`.
- Publish after the write, in a try/catch. A failed publish never fails the write; the warning stays pending.
- `punishment.acknowledged` is not published: the proxy is subscribe-only. The case event records the acknowledgement.
- `POST /warn/{id}/acknowledge` is PROXY only. The first call sets `acknowledged_at` and records the case event; a repeat keeps
  the first time and answers 204; an owner that does not own the warning gets 404 and nothing changes.
- The login read `GET /warn/pending` is PROXY only. A failed read never refuses a login; the warning shows on the next one.
- Velocity `WarningHandler` shows a warning once per event or login. The acknowledge line runs `/blackhole warning ack <id>`
  and is accepted only from the owner.

## Appeals (decision 8)

- A warning is appealable with the manual wait (`min-days-manual`) and is the `STANDARD` tier.
- A full lift sets `lifted`, moves no slot and refunds the recorded `eloCost` once as `APPEAL_REFUND`. The cost went to the
  template's section, so `PunishmentEntity.getChargedSectionKey()` is the section to use, not `getSectionKey()`.
- A duration reduction is refused (400): a warning has no duration. A denial changes nothing.

## Keep the tests

Each of these has a test: never active, warn-window counting, acknowledgement once and PROXY only, event payload with no
personal data, appeal lift and refund, duration reduction refused. A change that touches one keeps its test.
