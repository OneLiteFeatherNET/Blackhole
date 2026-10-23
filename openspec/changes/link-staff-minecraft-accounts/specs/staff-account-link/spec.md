# Spec Delta

## Purpose

Defines how a staff member links their Minecraft account to their Entra identity with a one-time code proven from both sides, and how the link attributes in-game actions to the real person.

## ADDED Requirements

### Requirement: Link code issue
The backend SHALL issue a link code to a signed-in staff member with a human token and any staff role. The code MUST be 8 characters of Crockford base32, bound to the caller's `oid`, valid for the configured lifetime (default 5 minutes), stored only as a hash, and returned only in the issue response. Issuing a new code MUST invalidate the caller's previous code. A service principal MUST get 403.

#### Scenario: Staff member requests a code
- **WHEN** a signed-in viewer calls `POST /staff-link/code`
- **THEN** the response carries an 8 character code and its expiry five minutes ahead of the injected clock, and only a hash of the code is stored

#### Scenario: New code replaces the old one
- **WHEN** the same staff member requests a second code
- **THEN** confirming with the first code fails and confirming with the second can succeed

#### Scenario: Service principal cannot request a code
- **WHEN** an app-only token calls `POST /staff-link/code`
- **THEN** the response is HTTP 403

### Requirement: Link confirmation by the proxy
The backend SHALL bind the Minecraft UUID sent as `onBehalfOf` to the `oid` the code was issued to, when `POST /staff-link/confirm` is called by the `PROXY` role with a live code. The code MUST be consumed on success. Any other role MUST get 403 and a missing `onBehalfOf` MUST get 400.

#### Scenario: Confirm success
- **WHEN** the proxy confirms a live code with the executing player's UUID
- **THEN** a link between that UUID and the code's `oid` exists, the code is consumed and `staff-link.created` is published

#### Scenario: Human token cannot confirm
- **WHEN** a signed-in staff member calls `POST /staff-link/confirm`
- **THEN** the response is HTTP 403

### Requirement: Invalid codes are indistinguishable
A wrong, expired, already used or burned code SHALL be refused with the same `INVALID_CODE` error. An expired code MUST be refused once the injected clock passes its expiry, and a used code MUST NOT link a second time.

#### Scenario: Code expired
- **WHEN** the clock is advanced beyond the lifetime and the proxy confirms the code
- **THEN** the response is `INVALID_CODE` and no link exists

#### Scenario: Code reused
- **WHEN** a code that already linked an account is confirmed again
- **THEN** the response is `INVALID_CODE` and the existing link is unchanged

#### Scenario: Wrong code
- **WHEN** the proxy confirms a code that was never issued
- **THEN** the response is `INVALID_CODE` and no link is created

### Requirement: Failed attempts burn a code
Each failed confirmation SHALL increment a failed-attempt counter stored on every live code, and a code that reaches the configured maximum (default 10) MUST be burned: later confirmations with it fail even with the right value. The counter is state on the code, not a rate limiter.

#### Scenario: Code burned after the cap
- **WHEN** ten wrong codes are confirmed while a code is live and then the correct code is confirmed
- **THEN** the response is `INVALID_CODE` and the staff member must issue a new code

#### Scenario: Below the cap
- **WHEN** nine wrong codes are confirmed and then the correct code
- **THEN** the link is created

### Requirement: One-to-one link
A Minecraft UUID SHALL belong to at most one `oid` and an `oid` to at most one UUID. Confirming a new pairing MUST replace the old link and publish `staff-link.removed` with reason `REPLACED` for each removed link.

#### Scenario: Staff member links a second account
- **WHEN** an already linked staff member confirms a code with another UUID
- **THEN** only the new link exists and the old one was reported as removed with reason `REPLACED`

#### Scenario: UUID already belongs to someone else
- **WHEN** a UUID linked to staff member A is confirmed with a code of staff member B
- **THEN** only the link of B exists and the link of A was removed with reason `REPLACED`

### Requirement: Unlinking
A staff member MUST be able to remove their own link with `DELETE /staff-link/me`, and an `ADMIN` MUST be able to list links and remove any link. Unlinking MUST publish `staff-link.removed` and MUST NOT change stored `actor_oid` values. Anonymizing a staff actor MUST also remove its link.

#### Scenario: Staff member unlinks
- **WHEN** a linked staff member calls `DELETE /staff-link/me`
- **THEN** the link is gone and `GET /staff-link/me` reports not linked

#### Scenario: Admin unlinks another person
- **WHEN** an admin calls `DELETE /admin/staff-link/{oid}`
- **THEN** that link is removed, while a moderator calling it gets HTTP 403

#### Scenario: Anonymize removes the link
- **WHEN** an admin anonymizes a linked staff actor
- **THEN** the display name is cleared and the link is removed

### Requirement: Attribution of in-game actions
When the proxy sends `onBehalfOf` for a linked UUID, the backend SHALL store the linked `oid` as `actor_oid` next to `source` and `recorded_by_oid`, and responses SHALL expose `actorOid` and `actorName`. For an unlinked UUID `actor_oid` MUST stay NULL. Creating a link MUST NOT change earlier records.

#### Scenario: In-game punishment by a linked staff member
- **WHEN** the proxy applies a punishment on behalf of a linked UUID
- **THEN** the punishment has the UUID as `source`, the proxy as `recorded_by_oid` and the staff member's `oid` as `actor_oid`, and the timeline shows the staff member's name

#### Scenario: In-game punishment by an unlinked staff member
- **WHEN** the proxy applies a punishment on behalf of an unlinked UUID
- **THEN** `actor_oid` is NULL and the timeline shows the UUID

#### Scenario: Linking does not rewrite history
- **WHEN** a link is created after earlier punishments by that UUID
- **THEN** those punishments keep a NULL `actor_oid`

### Requirement: Self-review covers in-game bans
The backend SHALL reject an appeal review with HTTP 403 when the reviewer's `oid` equals the punishment's `source`, `recorded_by_oid` or `actor_oid`, or when the punishment's `source` is currently linked to the reviewer's `oid`.

#### Scenario: Staff member reviews their own in-game ban
- **WHEN** a staff member who banned in game through a linked account decides the appeal in the panel
- **THEN** the response is HTTP 403 and the appeal stays undecided

#### Scenario: Pre-link ban
- **WHEN** a staff member links after banning in game and then decides that appeal
- **THEN** the response is HTTP 403 because the ban's source is linked to the reviewer

#### Scenario: Another moderator reviews
- **WHEN** a different moderator decides the appeal of that in-game ban
- **THEN** the decision is recorded for that moderator

### Requirement: Secrets stay out of logs and events
The plain link code and its hash MUST NOT appear in logs, events, error bodies or the OpenAPI examples. Logs and events SHALL identify the person by `oid`.

#### Scenario: Events carry the oid only
- **WHEN** a link is created
- **THEN** the `staff-link.created` payload contains the `oid` and no code

### Requirement: Minecraft link command
The Velocity plugin SHALL provide `/blackhole link <code>` under the permission `blackhole.link`, for players only, calling the confirmation endpoint with the executing player's UUID as `onBehalfOf`. It SHALL answer in English or German in the shared `bh:` message style for success, invalid or expired code, already linked, wrong usage and console use, and `/blackhole whoami` SHALL show the link status.

#### Scenario: Successful link
- **WHEN** a player with `blackhole.link` runs the command with a live code
- **THEN** a success message names the linked staff member

#### Scenario: Invalid or expired code
- **WHEN** the backend answers `INVALID_CODE`
- **THEN** the player sees an error that tells them to issue a new code in the panel

#### Scenario: Already linked
- **WHEN** the player's UUID is already linked
- **THEN** the message says so and how to link again

#### Scenario: Console sender
- **WHEN** the console runs the command
- **THEN** it is refused with a message that it is for players

#### Scenario: No permission
- **WHEN** a player without `blackhole.link` runs it
- **THEN** the proxy's own permission message is shown and no backend call is made

### Requirement: Account link dialog in the admin UI
The admin UI SHALL offer a "Minecraft-Konto verknüpfen" dialog that issues a code, shows it with a countdown and a copy button, shows the link status, and unlinks after confirmation, in German and English. Admins SHALL see a list of links with unlink. The dialog MUST be keyboard operable and not announce the countdown every second.

#### Scenario: Code shown with countdown
- **WHEN** a staff member clicks the link button
- **THEN** the dialog shows the code, the remaining time counting down and the command to type

#### Scenario: Code expires
- **WHEN** the countdown reaches zero
- **THEN** the code is cleared, expiry is announced once and a new code can be requested

#### Scenario: Unlink
- **WHEN** a linked staff member confirms unlinking
- **THEN** the status shows not linked

#### Scenario: Non-admin sees no list
- **WHEN** a moderator opens the account dialog
- **THEN** no list of other people's links is shown
