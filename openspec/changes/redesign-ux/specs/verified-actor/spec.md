## MODIFIED Requirements

### Requirement: Staff data retention and anonymization
Staff identity data SHALL be retained for as long as records referencing it exist. An `ADMIN` MUST be able to call `POST /admin/staff-actor/{oid}/anonymize`, which clears the display name of the `staff_actor`, the display name and avatar of the `staff_profile`, and the name snapshots of that `oid`'s chat events, keeps the `oid` and all history, regenerates the stored protocols that name the person, and is audited with the admin's own verified actor.

#### Scenario: Anonymize a former moderator
- **WHEN** an admin anonymizes a `staff_actor`
- **THEN** its display name is empty, existing records still reference the `oid`, the staff profile's name and avatar are removed, and the action is recorded with the admin's `oid`

#### Scenario: Moderator cannot anonymize
- **WHEN** a moderator calls the anonymize route
- **THEN** the response is HTTP 403

#### Scenario: Chat names removed
- **WHEN** an admin anonymizes a staff member who wrote chat messages
- **THEN** those messages keep their text and order, and their name snapshots are null

#### Scenario: Protocols regenerated
- **WHEN** an admin anonymizes a staff member named in a stored protocol
- **THEN** a new protocol version without the name is stored
