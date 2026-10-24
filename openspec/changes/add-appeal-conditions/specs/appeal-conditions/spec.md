# Spec Delta

## Purpose
Gives the appeal a versioned set of conditions that the team maintains, that the portal shows, and that a player confirms before filing.

## ADDED Requirements

### Requirement: Versioned, append-only conditions text
The system SHALL keep the appeal conditions as a plain text with a version number. Each save MUST create a new version numbered one higher than the highest existing one; an existing version MUST NOT be changed or deleted. A default English text MUST exist as version 1 on a fresh installation. A text is accepted when it is 1 to 4000 characters after trimming and contains no control character except newline.

#### Scenario: Default text
- **WHEN** the system is installed
- **THEN** the current conditions are version 1 and contain the default English text

#### Scenario: Save creates a version
- **WHEN** an administrator saves a new text while version 3 is current
- **THEN** version 4 is current and versions 1 to 3 are still readable unchanged

#### Scenario: Invalid text
- **WHEN** an administrator saves an empty text, a text over 4000 characters or a text with a control character
- **THEN** the answer is HTTP 400 and no version is created

### Requirement: Staff routes for the conditions
`GET /appeal-conditions` and `GET /appeal-conditions/versions` SHALL be readable by `VIEWER` and above. `POST /appeal-conditions` SHALL need `ADMIN`. The proxy, the Discord bot and a player session MUST be denied on all three. The saving staff member's verified `oid` MUST be stored with the version.

#### Scenario: Viewer reads
- **WHEN** a viewer calls `GET /appeal-conditions`
- **THEN** the answer is HTTP 200 with the current version, text and creation time

#### Scenario: Moderator cannot edit
- **WHEN** a moderator calls `POST /appeal-conditions`
- **THEN** the answer is HTTP 403 and no version is created

#### Scenario: Version list
- **WHEN** a viewer calls `GET /appeal-conditions/versions`
- **THEN** all versions are returned newest first

### Requirement: Public conditions route
`GET /public/appeal/conditions` SHALL return the current version number and text to any valid player session, `Cache-Control: no-store`. It MUST NOT return who saved a version. It MUST NOT be reachable without a player session.

#### Scenario: Player reads
- **WHEN** a logged-in player calls `GET /public/appeal/conditions`
- **THEN** the answer is HTTP 200 with `version` and `text`

#### Scenario: No session
- **WHEN** the route is called without a session token
- **THEN** the answer is HTTP 401

### Requirement: Filing needs the current conditions version
`POST /public/appeal` SHALL require `conditionsVersion` to equal the current version. A missing, older or newer value MUST be answered HTTP 409 with code `CONDITIONS_CHANGED` and no appeal MUST be recorded. The refusals for a forfeited, frozen or not yet eligible appeal keep precedence. The filed appeal MUST store the accepted version number and no other data about the acceptance.

#### Scenario: Current version
- **WHEN** a player files with the current `conditionsVersion`
- **THEN** the appeal is filed and its stored conditions version equals that number

#### Scenario: Changed meanwhile
- **WHEN** a staff member saves a new version after the player loaded the form and the player files with the old number
- **THEN** the answer is HTTP 409 `CONDITIONS_CHANGED` and no appeal exists

#### Scenario: Missing version
- **WHEN** a player files without `conditionsVersion`
- **THEN** the answer is HTTP 409 `CONDITIONS_CHANGED`

#### Scenario: Earlier refusal wins
- **WHEN** the Ban ID is marked leaked and the player files with a stale version
- **THEN** the answer is HTTP 409 `FORFEITED`

### Requirement: Portal shows and confirms the conditions
The portal SHALL show the current conditions on the appeal form before filing and keep the file button disabled until the player ticks a confirmation checkbox. It SHALL send the version it showed. On `CONDITIONS_CHANGED` it MUST reload the text, clear the confirmation and tell the player. The conversation page SHALL offer the conditions in a collapsible "Appeal conditions" block at the top. The text MUST be rendered as text, never as HTML, and the portal copy is English only.

#### Scenario: Not confirmed
- **WHEN** the player has not ticked the checkbox
- **THEN** the file button is disabled and no request is sent

#### Scenario: Confirmed
- **WHEN** the player ticks the checkbox and files
- **THEN** the request carries the shown `conditionsVersion`

#### Scenario: Changed text
- **WHEN** the backend answers `CONDITIONS_CHANGED`
- **THEN** the new text is shown, the checkbox is cleared and a message explains that the conditions changed

#### Scenario: Markup in the text
- **WHEN** the text contains `<script>` or `<b>`
- **THEN** it appears as literal characters and creates no element

#### Scenario: Viewable in the conversation
- **WHEN** the player opens the conversation page
- **THEN** a collapsed "Appeal conditions" block is at the top and shows the text when opened

### Requirement: Discord ticket follow-up
Applying the same version check to `POST /discord/tickets` SHALL be done together with the bot showing the conditions, and is not part of this change.

#### Scenario: Not yet applied
- **WHEN** the bot opens a ticket in this version of the system
- **THEN** no conditions version is required
