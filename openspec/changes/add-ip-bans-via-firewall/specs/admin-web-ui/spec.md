## ADDED Requirements

### Requirement: IP bans page
The admin panel SHALL provide an IP bans page listing active and lifted IP bans with their range, reason, source, expiry and actor. It SHALL offer creation (single address or CIDR, reason, expiry) to MODERATOR and ADMIN, and lifting with a mandatory reason. MODERATOR SHALL NOT see the permanent option.

#### Scenario: Moderator creates a temporary ban
- **WHEN** a moderator fills the form with a valid address and a 7-day expiry
- **THEN** the ban appears in the list as active

#### Scenario: Moderator and permanent
- **WHEN** a moderator opens the form
- **THEN** the permanent option is not offered

#### Scenario: Lift requires a reason
- **WHEN** a staff member lifts a ban without a reason
- **THEN** submit is disabled and the field explains why

### Requirement: Ban IP on the player page
The player page SHALL show the last addresses on record and offer "Ban IP" for each. The network ban dialog SHALL offer "also ban last IP" with the count of other accounts seen on that address, and SHALL show the hint "no address on record" when there is none.

#### Scenario: Shared address warning
- **WHEN** a moderator opens the ban dialog with "also ban last IP" for an address used by five other accounts
- **THEN** the dialog shows the count of other accounts before submit
