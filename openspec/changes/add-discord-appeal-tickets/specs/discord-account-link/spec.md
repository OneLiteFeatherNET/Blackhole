# Spec Delta

## Purpose
Lets a staff member or a player link a Discord account to their identity with a one-time code typed as `/link <code>` in Discord, so the bot can attribute and notify, and so the Discord user id stays erasable personal data. Ships first as the Discord bot MVP (`add-discord-appeal-tickets`, phase 0), before any ticket channel.

## ADDED Requirements

### Requirement: Staff link code
The backend SHALL issue a signed-in staff member (human token, any staff role) a link code through `POST /staff-discord-link/code`. A service principal MUST get 403. The code MUST be 8 characters of Crockford base32 and valid for the configured lifetime (default 10 minutes).

#### Scenario: Staff code
- **WHEN** a signed-in viewer calls `POST /staff-discord-link/code`
- **THEN** the response carries an 8 character code and an expiry ten minutes ahead of the injected clock

#### Scenario: Service principal
- **WHEN** an app-only token calls `POST /staff-discord-link/code`
- **THEN** the response is HTTP 403

### Requirement: Player link code
The backend SHALL issue a player a link code through `POST /player-discord-link/code` for role `PROXY` only, with the hashed owner in the body. A raw UUID or a malformed owner MUST get 400 and any other role 403.

#### Scenario: Player code from the proxy
- **WHEN** the proxy calls `POST /player-discord-link/code` with a hashed owner
- **THEN** a code is issued for that owner, and a raw UUID instead of a hash gets HTTP 400

### Requirement: Codes are single use and stored hashed
A code SHALL be stored only as a hash, returned only in the issue response and consumed on success. A new code MUST invalidate the subject's previous code. There MUST be no failed-attempt counter in the application (infra owns rate limiting).

#### Scenario: New code replaces the old one
- **WHEN** the same subject requests a second code
- **THEN** confirming with the first code fails and confirming with the second can succeed

### Requirement: Confirmation by the bot
The backend SHALL bind the Discord user id sent by the bot to the subject the code was issued to when `POST /discord/link/confirm` is called with role `DISCORD` and a live code, and consume the code. Wrong, expired and used codes MUST be indistinguishable (`INVALID_CODE`). A malformed Discord user id MUST get 400 without consuming the code.

#### Scenario: Staff link success
- **WHEN** the bot confirms a live staff code for a Discord user
- **THEN** the link exists, the code is consumed and `discord-link.created` is published with the kind and the oid but without the Discord user id

#### Scenario: Player link success
- **WHEN** the bot confirms a live player code
- **THEN** the hashed owner is bound to the Discord user id and the response names no staff member

#### Scenario: Invalid code
- **WHEN** the bot confirms a wrong, expired or used code
- **THEN** the answer is the same `INVALID_CODE` in every case and no link is created

### Requirement: One to one pairing
A Discord user id SHALL belong to at most one subject and a subject to at most one Discord user id per kind. A new pairing replaces the old one and publishes `discord-link.removed` with reason `REPLACED`.

#### Scenario: Second Discord account
- **WHEN** a linked staff member links another Discord account
- **THEN** only the new link exists and the old one is reported removed with reason `REPLACED`

### Requirement: The Discord user id is erasable personal data
A Discord user id SHALL be stored only in the link row. No response (not even the admin list), event, log line or error MAY contain it. The bot's `DELETE /discord/link/{discordUserId}`, the staff member's `DELETE /staff-discord-link/me`, an admin's `DELETE /admin/discord-link/{id}`, the staff anonymise action and the erasure of a player profile MUST remove the link and with it the id.

#### Scenario: Unlink by the bot
- **WHEN** a linked Discord user runs `/unlink`
- **THEN** every link of that Discord user is removed and a second call answers 404

#### Scenario: Anonymise
- **WHEN** an admin anonymises a staff actor with a Discord link
- **THEN** the Discord link is removed

#### Scenario: Admin list
- **WHEN** an admin lists `GET /admin/discord-link`
- **THEN** each item has the kind, the subject (oid or hashed owner), the staff name and the link time, and no Discord user id

### Requirement: Who am I
The backend SHALL answer `GET /discord/link/{discordUserId}` (role `DISCORD`) with the kinds of link that Discord user has, with the staff member's display name for a staff link, or an empty list.

#### Scenario: Linked as staff and player
- **WHEN** one Discord user has a staff link and a player link
- **THEN** whoami lists both kinds
