## ADDED Requirements

### Requirement: Sessions section on the player page
The player page SHALL show a "Sessions" section for every role that may open the page, listing the player's sessions from `GET /intel/players/{owner}/sessions`, newest first and paginated. Each row SHALL show the start time, the outcome, the IP address, the ASN organisation, the country (marked as approximate), the client brand and version, the protocol version and the virtual host. The section SHALL show the attribution text of the IP database in its footer.

#### Scenario: Sessions listed
- **WHEN** a moderator opens a player with three sessions
- **THEN** the section lists the three sessions newest first with their IP and provider

#### Scenario: No sessions
- **WHEN** the backend returns no sessions
- **THEN** the section says no session has been recorded yet

#### Scenario: Enrichment missing
- **WHEN** a session has no ASN or country
- **THEN** those cells show a dash and the row is still listed

### Requirement: Sessions are not kept in the browser
The sessions section MUST NOT write IP addresses, ASN organisations or session data to browser storage.

#### Scenario: Reload after viewing sessions
- **WHEN** a user views a player's sessions and reloads the browser
- **THEN** no IP address is restored from browser storage

## MODIFIED Requirements

### Requirement: No personal data retained by the UI
The UI MUST NOT persist player names, UUIDs, owner hashes or IP addresses in browser storage; only theme, language and the optional acting-as label MAY be stored.

#### Scenario: Reload after lookup
- **WHEN** a user looks up a player and reloads the browser
- **THEN** no player identifier or IP address is restored from browser storage
