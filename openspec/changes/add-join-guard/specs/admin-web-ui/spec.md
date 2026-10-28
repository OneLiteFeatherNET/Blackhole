## ADDED Requirements

### Requirement: Join guard page
The admin UI SHALL offer a join guard page for MODERATOR and ADMIN. It SHALL show the effective level and its source (manual, unattended or cleared), the manual and automatic level, the staff presence (count and last seen time, without any player name), the configured unattended level and night window, and the level history with the most recent 100 changes. A level selector SHALL set the manual level.

#### Scenario: Unattended level shown with its source
- **WHEN** the effective level is `STRICT` from the unattended evaluator
- **THEN** the page shows `STRICT` with the source unattended and the time since staff were last seen

#### Scenario: History without addresses
- **WHEN** the history contains changes
- **THEN** no row shows an IP address, an owner hash or a player name

### Requirement: Area bans page
The admin UI SHALL offer an area bans page for MODERATOR and ADMIN. It SHALL list active, expired and lifted area bans with kind, value, source, reason, actor, expiry and lift details, create ASN and country bans, and lift a ban with a required reason. The country option and the permanent option SHALL be hidden from a MODERATOR, and the server response remains the authority.

#### Scenario: Moderator form
- **WHEN** a MODERATOR opens the create form
- **THEN** only the ASN option and a maximum of seven days are offered

#### Scenario: Lift requires a reason
- **WHEN** a user lifts an area ban without entering a reason
- **THEN** the lift is not sent

### Requirement: Guard status on the dashboard
The dashboard SHALL show a tile with the effective guard level and the number of active area bans, and the tile SHALL link to the join guard page.

#### Scenario: Dashboard tile
- **WHEN** the effective level is `OFF` and two area bans are active
- **THEN** the tile shows `OFF` and 2 active area bans
