## ADDED Requirements

### Requirement: Exemption report from the proxy

The proxy SHALL read the permissions `blackhole.exempt` and `blackhole.exempt.<section>` of an online player at login and with each enforcement request for that player, and SHALL report them to the backend as exemption facts. The proxy MUST NOT take a punishment decision from these permissions; the backend decides.

#### Scenario: Login report

- **WHEN** a player with `blackhole.exempt.survival` joins
- **THEN** the proxy reports the section `survival` for that player's owner at login

#### Scenario: No decision on the proxy

- **WHEN** an exempt player is online and a backend write refuses a staff action against them
- **THEN** the proxy neither changes the player's existing punishment nor kicks the player

### Requirement: Refusal and badge messages

When the backend refuses a staff command with `TARGET_EXEMPT`, the proxy SHALL show the refusal with an exemption note in the existing message style, and SHALL show the exemption badge in the staff messages that list a target. The messages MUST NOT contain an owner hash or a UUID.

#### Scenario: Staff command refused

- **WHEN** a moderator runs `/punish` against an exempt player and the backend answers `TARGET_EXEMPT`
- **THEN** the moderator sees the refusal with the exemption note and no action is taken

#### Scenario: Badge in the report message

- **WHEN** a staff message lists an exempt player
- **THEN** the message carries the exemption badge and contains no owner hash
