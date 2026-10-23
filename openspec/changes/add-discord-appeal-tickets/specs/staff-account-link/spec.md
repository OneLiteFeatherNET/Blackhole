# Spec Delta

## Purpose
Lets a staff member link a Discord account with the same one-time code, so their Discord messages can be attributed to them.

## ADDED Requirements

### Requirement: Discord link with the same code
The backend SHALL accept a staff link code from the Discord bot through `POST /discord/staff-link/confirm` with the Discord user id, bind that id to the `oid` the code was issued to and consume the code. Wrong, expired and used codes MUST be indistinguishable. A Discord user id belongs to at most one `oid` and an `oid` to at most one Discord user id; a new pairing replaces the old and publishes `staff-link.removed` with reason `REPLACED`. The Discord id MUST NOT appear in events or logs.

#### Scenario: Link success
- **WHEN** the bot confirms a live code for a Discord user
- **THEN** the link exists, the code is consumed and later messages of that user with the staff role are `VERIFIED`

#### Scenario: Invalid code
- **WHEN** the bot confirms a wrong, expired or used code
- **THEN** the answer is the same `INVALID_CODE` in every case and no link is created

#### Scenario: Second Discord account
- **WHEN** a linked staff member links another Discord account
- **THEN** only the new link exists and the old one is reported removed with reason `REPLACED`

### Requirement: Unlinking and anonymisation
A staff member SHALL be able to remove their Discord link with `/unlink`, and the staff anonymise action MUST remove it.

#### Scenario: Unlink
- **WHEN** a linked member runs `/unlink`
- **THEN** their later messages are `UNVERIFIED` staff

#### Scenario: Anonymise
- **WHEN** an admin anonymises a staff actor
- **THEN** the Discord link is removed
