## ADDED Requirements

### Requirement: Also ban last IP on a network ban
A network ban request MAY carry the flag `alsoBanLastIp` (default false). When it is set and the player has a last address on record, the system SHALL create an IP ban for that address in the same request, with source `PUNISHMENT`, the punishment's id and case, and the punishment's expiry (no expiry for a permanent punishment). The flag MUST be refused for any punishment that is not a network ban.

#### Scenario: Network ban with last IP
- **WHEN** a moderator applies a 7-day network ban with `alsoBanLastIp` and the player has a last address
- **THEN** the punishment and an IP ban for that address with the same expiry are created in the same request

#### Scenario: No address on record
- **WHEN** the flag is set and the player has no address on record
- **THEN** the punishment is applied, no IP ban is created, and the response carries the warning `NO_IP_ON_RECORD`

#### Scenario: Flag on a mute
- **WHEN** the flag is set on a mute
- **THEN** the response is HTTP 400 and nothing is created

### Requirement: Case event for an IP ban
Creating or lifting an IP ban that has a case SHALL add a case event of kind `ip_banned` or `ip_lifted` to that case, carrying the kind of change and the actor, and MUST NOT carry the address.

#### Scenario: Case timeline
- **WHEN** an IP ban is created from a case
- **THEN** the case shows an `ip_banned` event without the address
