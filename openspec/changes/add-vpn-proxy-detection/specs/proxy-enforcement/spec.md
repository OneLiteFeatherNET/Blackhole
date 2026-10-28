## ADDED Requirements

### Requirement: VPN policy on login
After the existing ban checks, the proxy SHALL call `POST /intel/login-check` with the player's owner hash and address. A verdict of `DENY` MUST refuse the login with the `VPN_DENY` screen. A verdict of `ALLOW` or `FLAG` MUST let the login proceed without any visible change for the player.

#### Scenario: Denied VPN login
- **WHEN** a player logs in from a VPN address and the VPN policy is `DENY`
- **THEN** the player is refused with the VPN screen

#### Scenario: Flagged login
- **WHEN** a player logs in from a hosting address and the hosting policy is `FLAG`
- **THEN** the player joins normally and nothing is shown to the player

### Requirement: VPN denial screen
The `VPN_DENY` screen SHALL say that VPN and hosting connections are not allowed on this network. It MUST NOT show the player's address, the classification source or any account detail, and MUST be localized with the English text as fallback, like the other screens.

#### Scenario: German client
- **WHEN** a player with a German client language is denied
- **THEN** the screen is shown in German if the message exists, otherwise in English

#### Scenario: No address on screen
- **WHEN** the screen is rendered for any denial
- **THEN** it contains no IP address

### Requirement: Login check timeout
The login check SHALL use the timeout `blackhole.intel.login-check.timeout` (default `PT1S`) and MUST NOT block the login beyond it. A timeout, error or unknown verdict SHALL allow the login and count a fail-open event.

#### Scenario: Slow backend
- **WHEN** the backend does not answer within the timeout
- **THEN** the login is allowed and the fail-open counter increases
