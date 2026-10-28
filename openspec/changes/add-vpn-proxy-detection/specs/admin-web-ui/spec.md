## ADDED Requirements

### Requirement: VPN policy page
The admin panel SHALL provide a policy page showing the three class policies (`VPN`, `HOSTING`, `PROXY`), the hosting ASN list and the list sources with their attribution, refresh time, entry count and status. Only ADMIN SHALL be able to change a policy, and only ADMIN SHALL see the `DENY` option.

#### Scenario: Moderator opens the policy page
- **WHEN** a moderator opens the policy page
- **THEN** the policies are shown read-only and no change control is offered

#### Scenario: Admin changes a policy
- **WHEN** an admin changes the `VPN` policy to `FLAG`
- **THEN** a confirmation shows the previous value before saving

#### Scenario: Source status
- **WHEN** a list source failed its last refresh
- **THEN** its card shows "failed" with the time of the last successful refresh

### Requirement: Allow-list page
The admin panel SHALL provide an allow-list page for adding and removing exemptions. The reason is required. MODERATOR SHALL NOT see the permanent option. Expired entries SHALL be shown as expired.

#### Scenario: Reason required
- **WHEN** an exemption is submitted without a reason
- **THEN** submit is disabled and the field explains why

#### Scenario: Expired entry
- **WHEN** an exemption has passed its expiry
- **THEN** the entry is shown as expired and no longer applies

### Requirement: Hits on the player page
The player page SHALL list the VPN hits of the player with their class, source, verdict and time, and SHALL show whether the player is on the allow-list. Hits SHALL NOT be shown to any caller without a staff role.

#### Scenario: Player with a flagged login
- **WHEN** a moderator opens a player whose last login was flagged
- **THEN** the hit is listed with its class and source
