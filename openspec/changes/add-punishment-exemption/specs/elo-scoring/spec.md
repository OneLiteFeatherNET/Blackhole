## MODIFIED Requirements

### Requirement: Soft threshold triggers automatic temporary punishment
When a track score crosses below `blackhole.elo.soft-threshold` (default 700) but not below the perma-ban threshold, the system SHALL automatically apply a temporary punishment attributed to the fixed ELO system source: a CHAT mute for the chat track (duration `auto-ban.chat-soft-duration`, default PT24H) or a NETWORK ban for the gameplay track (duration `auto-ban.gameplay-soft-duration`, default P3D). When the crossed player is exempt under `punishment-exemption`, the system MUST NOT apply the punishment and MUST create an open review item for an admin instead, while keeping the score change.

#### Scenario: Chat score drops below the soft threshold
- **WHEN** a player's CHAT score moves from at or above the soft threshold to below it
- **THEN** a temporary CHAT punishment is applied using the automatic chat timeout template
- **AND** the punishment metadata records the reason code of the triggering signal

#### Scenario: Automatic template is created on demand
- **WHEN** a soft-threshold crossing occurs and no automatic template for that track exists yet
- **THEN** the system creates one with the configured soft duration and an ELO delta of zero, and reuses it for later crossings

#### Scenario: Exempt player crosses the soft threshold
- **WHEN** an exempt player's score crosses the soft threshold
- **THEN** no punishment is applied, the score change is kept, and one open review item exists for an admin

### Requirement: Perma-ban threshold triggers permanent ban
When a track score crosses below `blackhole.elo.perma-ban-threshold` (default 300), the system SHALL apply the operator-configured perma-ban template for that track (`perma-ban-template.chat-id` / `perma-ban-template.gameplay-id`), attributed to the ELO system source. If no template is configured or the configured template no longer exists, the system MUST skip the ban, log a warning and never invent a template. When the crossed player is exempt under `punishment-exemption`, the system MUST NOT apply the ban and MUST create an open review item instead.

#### Scenario: Configured perma-ban template is applied
- **WHEN** a player's GAMEPLAY score crosses below the perma-ban threshold and a gameplay perma-ban template is configured
- **THEN** that template is applied to the player
- **AND** the soft-threshold punishment is not additionally applied for the same delta

#### Scenario: No perma-ban template configured
- **WHEN** a player's CHAT score crosses below the perma-ban threshold and no chat perma-ban template is configured
- **THEN** no punishment is applied and a warning is logged
- **AND** the threshold-crossed event reports `templateConfigured` as false

#### Scenario: Exempt player crosses the perma-ban threshold
- **WHEN** an exempt player's GAMEPLAY score crosses below the perma-ban threshold
- **THEN** no ban is applied and one open review item exists with the perma-ban template as its proposed action
