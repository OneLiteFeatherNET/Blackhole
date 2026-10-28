## ADDED Requirements

### Requirement: Template editor shows the public reason

The template editor SHALL show an optional "Public reason" field next to the staff reason. The field SHALL state that the text is published on the public ban list, SHALL show its length against the 200 character limit, and SHALL be empty when no public reason is set. The editor SHALL show the field only to users with the ADMIN role and SHALL show a read-only note to other users. A save that the backend refuses for the public reason SHALL show the validation message next to the field.

#### Scenario: Admin sets a public reason

- **WHEN** an ADMIN enters a public reason and saves the template
- **THEN** the template is saved with that public reason and the staff reason is unchanged

#### Scenario: Moderator sees the note

- **WHEN** a MODERATOR opens the template editor
- **THEN** the public reason is shown read-only with the note that only admins set it

#### Scenario: Refused text

- **WHEN** an ADMIN saves a public reason that contains an IP address
- **THEN** the field shows the validation message and the text is not saved
