# Spec Delta

## Purpose
Uses the staff's Discord link (capability `discord-account-link`) to grant ticket-channel access. The link itself, its codes and its erasure are specified in `discord-account-link`.

## ADDED Requirements

### Requirement: The link is what grants channel access
A live Discord staff link SHALL be required, together with the staff role, for a member to be added to ticket channels, and `GET /discord/staff-links` SHALL return the linked Discord user ids to the bot only (role `DISCORD`). Removing a link by `/unlink`, replacement or staff anonymisation MUST end the member's channel access at the next reconcile, and the id list MUST NOT appear in events, logs or the panel.

#### Scenario: Bot reads the links
- **WHEN** the bot calls `GET /discord/staff-links`
- **THEN** it receives the linked Discord user ids and a staff token calling it gets HTTP 403

#### Scenario: Anonymised staff
- **WHEN** an admin anonymises a staff actor with a Discord link
- **THEN** the link is removed and the member loses channel access at the next reconcile
