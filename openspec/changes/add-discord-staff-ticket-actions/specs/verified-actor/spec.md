## ADDED Requirements

### Requirement: Delegated attribution by the Discord bot

A `DISCORD` caller MAY send `onBehalfOf` (a staff member's Entra object id) only on the appeal review, the case update, the ticket close and the ticket freeze routes. The server SHALL attribute the action to that staff member, and `recorded_by_oid` SHALL be the bot's `oid`.

#### Scenario: Linked moderator decides from Discord

- **WHEN** the bot posts an appeal review with `onBehalfOf` set to a linked moderator with a fresh snapshot
- **THEN** the review is attributed to the moderator and `recorded_by_oid` is the bot's `oid`

#### Scenario: Bot on another route

- **WHEN** the bot sends `onBehalfOf` to a punishment route
- **THEN** the response is 400 and nothing is written

### Requirement: Discord delegation is checked

On the four routes, a `DISCORD` caller SHALL be refused with 403 unless the named staff member has a live Discord staff link and a fresh role snapshot that holds MODERATOR or ADMIN. A `DISCORD` caller on those routes without `onBehalfOf` SHALL be refused with 403.

#### Scenario: Unlinked oid

- **WHEN** the bot sends `onBehalfOf` for an oid with no live staff link
- **THEN** the response is 403 `STAFF_NOT_ELIGIBLE` and nothing is written

#### Scenario: Missing onBehalfOf

- **WHEN** the bot calls the appeal review without `onBehalfOf`
- **THEN** the response is 403 and nothing is written

### Requirement: Staff role snapshot

For every verified request of a HUMAN caller, the backend SHALL store the staff roles of the token (only the names `ADMIN`, `MODERATOR` and `VIEWER`) and the time of that request on the caller's `staff_actor` row. The snapshot MUST NOT be written for SERVICE callers and MUST NOT store any other claim.

#### Scenario: Moderator signs in

- **WHEN** a moderator sends a verified request
- **THEN** the `staff_actor` row holds `MODERATOR` in its roles and the request time as `roles_seen_at`

#### Scenario: Proxy request

- **WHEN** a request is made with the proxy's app-only token
- **THEN** no role snapshot is written for it
