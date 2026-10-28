## ADDED Requirements

### Requirement: Linked accounts for one owner
The system SHALL answer `GET /evasion/{owner}/alts` for VIEWER and above and for PROXY with the other owners linked to `owner` by a shared address or a shared client fingerprint inside the detection window. The address link SHALL use the clear IP of session records and the keyed token of both legacy sightings and session records.

#### Scenario: Shared address
- **WHEN** owner B has a session from the clear IP that owner A used 2 days earlier
- **THEN** the linked accounts of A list B with reason `SHARED_ADDRESS`

#### Scenario: Shared fingerprint
- **WHEN** owner B shares a client fingerprint with owner A inside its window
- **THEN** the linked accounts of A list B with reason `SHARED_FINGERPRINT`

#### Scenario: Both reasons
- **WHEN** owner B shares an address and a fingerprint with owner A
- **THEN** B is listed once with both reasons

#### Scenario: Outside the window
- **WHEN** owner B used the same address more than the detection window before owner A
- **THEN** B is not listed

#### Scenario: Salt not configured
- **WHEN** no IP salt is configured and owner B has a session from the same clear IP as owner A
- **THEN** B is listed with reason `SHARED_ADDRESS`

#### Scenario: Malformed owner
- **WHEN** the owner is not a 128-character hex string
- **THEN** the response is a validation error

### Requirement: Contents of a linked account
Each linked account SHALL carry its owner hash, its most recent name when known, its reasons (`SHARED_ADDRESS`, `SHARED_FINGERPRINT`), its last seen time and the sections in which it has an active punishment. The list SHALL hold at most 20 accounts, newest first, and SHALL say when it was cut short. The response MUST NOT contain an IP address, a token or a raw UUID.

#### Scenario: Active punishments listed
- **WHEN** a linked account has an active network ban
- **THEN** its entry lists the section `network`, and a lifted ban or a kick is not listed

#### Scenario: More than twenty accounts
- **WHEN** 25 accounts are linked
- **THEN** the response holds the 20 newest and says the list was cut short

#### Scenario: No address in the response
- **WHEN** a viewer requests the linked accounts of an owner that shares an address
- **THEN** the body contains no IP address and no token, and each linked account carries its raw UUID
